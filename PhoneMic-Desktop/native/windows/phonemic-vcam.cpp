// phonemic-vcam.dll - "Phone Mic Camera", a Windows 11 virtual webcam (MFCreateVirtualCamera).
//
// How it fits together:
//   * The desktop app calls PhoneMicVCamStart(); Windows registers a session-lifetime virtual
//     camera whose media source is this DLL's COM class (registered in HKLM by regsvr32).
//   * When any app opens the camera, Windows' Frame Server service (svchost, LOCAL SERVICE,
//     session 0) loads this DLL and asks the Activator for a media source.
//   * The source's stream pulls NV12 frames from the desktop app over loopback TCP
//     (127.0.0.1:47654) - plain TCP needs no cross-session shared-memory privileges.
//     Without a live feed it shows a solid placeholder colour.
//
// The COM media-source structure (activator / source / stream and the attributes the Frame
// Server expects) follows VCamSample by Simon Mourier, MIT License, Copyright (c) 2024-2026
// Simon Mourier, https://github.com/smourier/VCamSample - rewritten here on plain WRL.

#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
// WRL (and the cguid.h it pulls in) must come before ks.h, which redefines GUID_NULL as a macro.
#include <wrl/client.h>
#include <wrl/implements.h>
#include <initguid.h>
#include <propvarutil.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <mfvirtualcamera.h>
#include <mferror.h>
#include <ks.h>
#include <ksproxy.h>
#include <ksmedia.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mf.lib")
#pragma comment(lib, "mfreadwrite.lib")
#pragma comment(lib, "mfsensorgroup.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "propsys.lib")
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "ws2_32.lib")

using Microsoft::WRL::ChainInterfaces;
using Microsoft::WRL::ClassicCom;
using Microsoft::WRL::ComPtr;
using Microsoft::WRL::Make;
using Microsoft::WRL::RuntimeClass;
using Microsoft::WRL::RuntimeClassFlags;

// {7A3E5C1B-4D2F-4B8A-9E61-0C5D2F8B7A43} - Phone Mic Camera media source
static const GUID CLSID_PhoneMicVCam = {0x7a3e5c1b, 0x4d2f, 0x4b8a, {0x9e, 0x61, 0x0c, 0x5d, 0x2f, 0x8b, 0x7a, 0x43}};

// Fixed output format: what every app sees. The desktop app scales whatever the phone sends.
static const UINT32 kWidth = 1280;
static const UINT32 kHeight = 720;
static const UINT32 kFps = 30;
static const size_t kFrameBytes = (size_t)kWidth * kHeight * 3 / 2;
static const UINT16 kFeedPort = 47654;
static const uint32_t kFeedMagic = 0x46564D50; // "PMVF" little-endian
static const ULONGLONG kFeedStaleMs = 1500;

static HMODULE g_module = nullptr;

#define RETURN_IF_FAILED(expr) do { HRESULT _hr = (expr); if (FAILED(_hr)) return _hr; } while (0)

// ---------------------------------------------------------------------------------------------
// Feed: latest frame from the desktop app, shared by every stream in this process.
// ---------------------------------------------------------------------------------------------
class Feed {
public:
    static Feed& Instance() {
        static Feed feed;
        return feed;
    }

    void AddUser() {
        std::lock_guard<std::mutex> guard(_threadLock);
        if (_users++ == 0) {
            _stop = false;
            _thread = std::thread([this] { Run(); });
        }
    }

    void RemoveUser() {
        std::lock_guard<std::mutex> guard(_threadLock);
        if (_users == 0 || --_users > 0) return;
        _stop = true;
        SOCKET s = _socket.exchange(INVALID_SOCKET);
        if (s != INVALID_SOCKET) closesocket(s); // unblocks recv()
        if (_thread.joinable()) _thread.join();
    }

    // Copies the latest live frame into an NV12 destination with the given pitch, or paints
    // the placeholder when the desktop app isn't feeding us.
    void CopyTo(BYTE* dst, LONG pitch) {
        std::lock_guard<std::mutex> guard(_frameLock);
        bool live = _hasFrame && GetTickCount64() - _frameTick < kFeedStaleMs;
        for (UINT32 y = 0; y < kHeight; y++) {
            BYTE* row = dst + (ptrdiff_t)y * pitch;
            if (live) memcpy(row, _frame.data() + (size_t)y * kWidth, kWidth);
            else memset(row, 41, kWidth); // Y of the app's background colour #0F2229
        }
        BYTE* uvDst = dst + (ptrdiff_t)kHeight * pitch;
        for (UINT32 y = 0; y < kHeight / 2; y++) {
            BYTE* row = uvDst + (ptrdiff_t)y * pitch;
            if (live) {
                memcpy(row, _frame.data() + (size_t)kWidth * kHeight + (size_t)y * kWidth, kWidth);
            } else {
                for (UINT32 x = 0; x < kWidth; x += 2) {
                    row[x] = 134;     // U
                    row[x + 1] = 119; // V
                }
            }
        }
    }

private:
    Feed() : _frame(kFrameBytes) {}

    static bool RecvAll(SOCKET s, char* buffer, int length) {
        int got = 0;
        while (got < length) {
            int r = recv(s, buffer + got, length - got, 0);
            if (r <= 0) return false;
            got += r;
        }
        return true;
    }

    void Run() {
        WSADATA wsa;
        if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return;
        std::vector<char> incoming(kFrameBytes);
        while (!_stop) {
            SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
            if (s == INVALID_SOCKET) {
                Sleep(500);
                continue;
            }
            sockaddr_in address = {};
            address.sin_family = AF_INET;
            address.sin_port = htons(kFeedPort);
            address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            _socket = s;
            if (connect(s, (sockaddr*)&address, sizeof(address)) == 0) {
                while (!_stop) {
                    uint32_t header[4]; // magic, width, height, length
                    if (!RecvAll(s, (char*)header, sizeof(header))) break;
                    if (header[0] != kFeedMagic || header[3] > 64u * 1024 * 1024) break;
                    if (header[3] > incoming.size()) incoming.resize(header[3]);
                    if (!RecvAll(s, incoming.data(), (int)header[3])) break;
                    if (header[1] != kWidth || header[2] != kHeight || header[3] != kFrameBytes) continue;
                    std::lock_guard<std::mutex> guard(_frameLock);
                    memcpy(_frame.data(), incoming.data(), kFrameBytes);
                    _hasFrame = true;
                    _frameTick = GetTickCount64();
                }
            }
            SOCKET mine = _socket.exchange(INVALID_SOCKET);
            if (mine != INVALID_SOCKET) closesocket(mine);
            for (int i = 0; i < 5 && !_stop; i++) Sleep(100); // desktop app not up yet: retry
        }
        WSACleanup();
    }

    std::mutex _threadLock;
    int _users = 0;
    std::thread _thread;
    std::atomic<bool> _stop{false};
    std::atomic<SOCKET> _socket{INVALID_SOCKET};

    std::mutex _frameLock;
    std::vector<BYTE> _frame;
    bool _hasFrame = false;
    ULONGLONG _frameTick = 0;
};

// ---------------------------------------------------------------------------------------------
// Media stream: one NV12 1280x720 @ 30 fps video capture stream.
// ---------------------------------------------------------------------------------------------
class MediaStream
    : public RuntimeClass<RuntimeClassFlags<ClassicCom>, ChainInterfaces<IMFMediaStream2, IMFMediaStream, IMFMediaEventGenerator>, IKsControl> {
public:
    ~MediaStream() {
        if (_timer) CloseHandle(_timer);
    }

    HRESULT Initialize(IMFMediaSource* source, DWORD id) {
        _source = source;
        _id = id;
        // Plain Sleep() rounds up to 15.6 ms ticks (30 fps came out as ~21); this timer doesn't.
        _timer = CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
        RETURN_IF_FAILED(MFCreateAttributes(&_attributes, 4));
        RETURN_IF_FAILED(SetStreamAttributes(_attributes.Get()));
        RETURN_IF_FAILED(MFCreateEventQueue(&_queue));

        ComPtr<IMFMediaType> type;
        RETURN_IF_FAILED(MFCreateMediaType(&type));
        RETURN_IF_FAILED(type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video));
        RETURN_IF_FAILED(type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12));
        RETURN_IF_FAILED(type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive));
        RETURN_IF_FAILED(type->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE));
        RETURN_IF_FAILED(MFSetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, kWidth, kHeight));
        RETURN_IF_FAILED(type->SetUINT32(MF_MT_DEFAULT_STRIDE, kWidth));
        RETURN_IF_FAILED(MFSetAttributeRatio(type.Get(), MF_MT_FRAME_RATE, kFps, 1));
        RETURN_IF_FAILED(MFSetAttributeRatio(type.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1));
        RETURN_IF_FAILED(type->SetUINT32(MF_MT_AVG_BITRATE, (UINT32)(kFrameBytes * 8 * kFps)));
        _type = type;

        IMFMediaType* types[] = {type.Get()};
        RETURN_IF_FAILED(MFCreateStreamDescriptor(_id, 1, types, &_descriptor));
        RETURN_IF_FAILED(SetStreamAttributes(_descriptor.Get()));
        ComPtr<IMFMediaTypeHandler> handler;
        RETURN_IF_FAILED(_descriptor->GetMediaTypeHandler(&handler));
        RETURN_IF_FAILED(handler->SetCurrentMediaType(type.Get()));
        return S_OK;
    }

    HRESULT Start(IMFMediaType* type) {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        if (!_queue) return MF_E_SHUTDOWN;
        if (type) _type = type;
        if (!_allocator) {
            // The Frame Server normally hands us its allocator; fall back to our own.
            RETURN_IF_FAILED(MFCreateVideoSampleAllocatorEx(IID_PPV_ARGS(&_allocator)));
        }
        RETURN_IF_FAILED(_allocator->InitializeSampleAllocator(10, _type.Get()));
        if (_state != MF_STREAM_STATE_RUNNING) Feed::Instance().AddUser();
        _state = MF_STREAM_STATE_RUNNING;
        _nextSample = 0;
        return _queue->QueueEventParamVar(MEStreamStarted, GUID_NULL, S_OK, nullptr);
    }

    HRESULT Stop() {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        if (!_queue) return MF_E_SHUTDOWN;
        if (_state == MF_STREAM_STATE_RUNNING) Feed::Instance().RemoveUser();
        if (_allocator) _allocator->UninitializeSampleAllocator();
        _state = MF_STREAM_STATE_STOPPED;
        return _queue->QueueEventParamVar(MEStreamStopped, GUID_NULL, S_OK, nullptr);
    }

    void Shutdown() {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        if (_state == MF_STREAM_STATE_RUNNING) Feed::Instance().RemoveUser();
        _state = MF_STREAM_STATE_STOPPED;
        if (_queue) _queue->Shutdown();
        _queue.Reset();
        _allocator.Reset();
        _source.Reset();
    }

    HRESULT SetAllocator(IUnknown* allocator) {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        _allocator.Reset();
        return allocator->QueryInterface(IID_PPV_ARGS(&_allocator));
    }

    // IMFMediaEventGenerator
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) override {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        return _queue ? _queue->BeginGetEvent(callback, state) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) override {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        return _queue ? _queue->EndGetEvent(result, event) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override {
        ComPtr<IMFMediaEventQueue> queue;
        {
            std::lock_guard<std::recursive_mutex> guard(_lock);
            queue = _queue;
        }
        return queue ? queue->GetEvent(flags, event) : MF_E_SHUTDOWN; // may block: outside the lock
    }
    STDMETHODIMP QueueEvent(MediaEventType type, REFGUID extended, HRESULT status, const PROPVARIANT* value) override {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        return _queue ? _queue->QueueEventParamVar(type, extended, status, value) : MF_E_SHUTDOWN;
    }

    // IMFMediaStream
    STDMETHODIMP GetMediaSource(IMFMediaSource** source) override {
        if (!source) return E_POINTER;
        std::lock_guard<std::recursive_mutex> guard(_lock);
        if (!_source) return MF_E_SHUTDOWN;
        return _source.CopyTo(source);
    }
    STDMETHODIMP GetStreamDescriptor(IMFStreamDescriptor** descriptor) override {
        if (!descriptor) return E_POINTER;
        std::lock_guard<std::recursive_mutex> guard(_lock);
        if (!_descriptor) return MF_E_SHUTDOWN;
        return _descriptor.CopyTo(descriptor);
    }
    STDMETHODIMP RequestSample(IUnknown* token) override {
        // Pace to 30 fps on a fixed schedule: consumers ask again as soon as a sample arrives.
        const LONGLONG frameTime = 10000000 / kFps;
        LONGLONG now = MFGetSystemTime();
        LONGLONG next = _nextSample;
        if (next == 0 || now - next > frameTime) {
            next = now; // first frame, or we fell behind: deliver now and restart the schedule
        } else if (next > now) {
            WaitFor(next - now);
        }
        _nextSample = next + frameTime;

        std::lock_guard<std::recursive_mutex> guard(_lock);
        if (!_queue || !_allocator) return MF_E_SHUTDOWN;
        ComPtr<IMFSample> sample;
        RETURN_IF_FAILED(_allocator->AllocateSample(&sample));
        ComPtr<IMFMediaBuffer> buffer;
        RETURN_IF_FAILED(sample->GetBufferByIndex(0, &buffer));

        ComPtr<IMF2DBuffer> buffer2d;
        if (SUCCEEDED(buffer.As(&buffer2d))) {
            BYTE* scan0 = nullptr;
            LONG pitch = 0;
            RETURN_IF_FAILED(buffer2d->Lock2D(&scan0, &pitch));
            Feed::Instance().CopyTo(scan0, pitch);
            buffer2d->Unlock2D();
        } else {
            BYTE* data = nullptr;
            DWORD capacity = 0;
            RETURN_IF_FAILED(buffer->Lock(&data, &capacity, nullptr));
            if (capacity >= kFrameBytes) Feed::Instance().CopyTo(data, (LONG)kWidth);
            buffer->Unlock();
        }
        RETURN_IF_FAILED(buffer->SetCurrentLength((DWORD)kFrameBytes));

        RETURN_IF_FAILED(sample->SetSampleTime(MFGetSystemTime()));
        RETURN_IF_FAILED(sample->SetSampleDuration(10000000 / kFps));
        if (token) RETURN_IF_FAILED(sample->SetUnknown(MFSampleExtension_Token, token));
        return _queue->QueueEventParamUnk(MEMediaSample, GUID_NULL, S_OK, sample.Get());
    }

    // IMFMediaStream2
    STDMETHODIMP SetStreamState(MF_STREAM_STATE value) override {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        if (_state == value) return S_OK;
        switch (value) {
        case MF_STREAM_STATE_PAUSED:
            if (_state != MF_STREAM_STATE_RUNNING) return MF_E_INVALID_STATE_TRANSITION;
            Feed::Instance().RemoveUser();
            _state = value;
            return S_OK;
        case MF_STREAM_STATE_RUNNING:
            return Start(nullptr);
        case MF_STREAM_STATE_STOPPED:
            return Stop();
        default:
            return MF_E_INVALID_STATE_TRANSITION;
        }
    }
    STDMETHODIMP GetStreamState(MF_STREAM_STATE* value) override {
        if (!value) return E_POINTER;
        *value = _state;
        return S_OK;
    }

    // IKsControl: no camera controls exposed.
    STDMETHODIMP KsProperty(PKSPROPERTY, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }
    STDMETHODIMP KsMethod(PKSMETHOD, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }
    STDMETHODIMP KsEvent(PKSEVENT, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }

    IMFAttributes* Attributes() { return _attributes.Get(); }
    MF_STREAM_STATE State() { return _state; }

private:
    void WaitFor(LONGLONG hundredNs) {
        if (!_timer) {
            Sleep((DWORD)(hundredNs / 10000));
            return;
        }
        LARGE_INTEGER due;
        due.QuadPart = -hundredNs; // negative = relative
        if (SetWaitableTimer(_timer, &due, 0, nullptr, nullptr, FALSE)) WaitForSingleObject(_timer, 100);
    }

    HRESULT SetStreamAttributes(IMFAttributes* attributes) {
        RETURN_IF_FAILED(attributes->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY, PINNAME_VIDEO_CAPTURE));
        RETURN_IF_FAILED(attributes->SetUINT32(MF_DEVICESTREAM_STREAM_ID, _id));
        RETURN_IF_FAILED(attributes->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED, 1));
        RETURN_IF_FAILED(attributes->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES, MFFrameSourceTypes_Color));
        return S_OK;
    }

    std::recursive_mutex _lock;
    ComPtr<IMFMediaSource> _source;
    ComPtr<IMFAttributes> _attributes;
    ComPtr<IMFMediaEventQueue> _queue;
    ComPtr<IMFStreamDescriptor> _descriptor;
    ComPtr<IMFMediaType> _type;
    ComPtr<IMFVideoSampleAllocatorEx> _allocator;
    DWORD _id = 0;
    std::atomic<MF_STREAM_STATE> _state{MF_STREAM_STATE_STOPPED};
    std::atomic<LONGLONG> _nextSample{0};
    HANDLE _timer = nullptr;
};

// ---------------------------------------------------------------------------------------------
// Media source: one stream, live, no device controls.
// ---------------------------------------------------------------------------------------------
class MediaSource
    : public RuntimeClass<RuntimeClassFlags<ClassicCom>, ChainInterfaces<IMFMediaSourceEx, IMFMediaSource, IMFMediaEventGenerator>,
                          IMFGetService, IKsControl, IMFSampleAllocatorControl> {
public:
    HRESULT Initialize(IMFAttributes* activatorAttributes) {
        RETURN_IF_FAILED(MFCreateAttributes(&_attributes, 8));
        if (activatorAttributes) RETURN_IF_FAILED(activatorAttributes->CopyAllItems(_attributes.Get()));

        ComPtr<IMFSensorProfileCollection> profiles;
        RETURN_IF_FAILED(MFCreateSensorProfileCollection(&profiles));
        ComPtr<IMFSensorProfile> profile;
        RETURN_IF_FAILED(MFCreateSensorProfile(KSCAMERAPROFILE_Legacy, 0, nullptr, &profile));
        RETURN_IF_FAILED(profile->AddProfileFilter(0, L"((RES==;FRT<=30,1;SUT==))"));
        RETURN_IF_FAILED(profiles->AddProfile(profile.Get()));
        RETURN_IF_FAILED(_attributes->SetUnknown(MF_DEVICEMFT_SENSORPROFILE_COLLECTION, profiles.Get()));

        _stream = Make<MediaStream>();
        if (!_stream) return E_OUTOFMEMORY;
        RETURN_IF_FAILED(_stream->Initialize(this, 0));
        ComPtr<IMFStreamDescriptor> descriptor;
        RETURN_IF_FAILED(_stream->GetStreamDescriptor(&descriptor));
        IMFStreamDescriptor* descriptors[] = {descriptor.Get()};
        RETURN_IF_FAILED(MFCreatePresentationDescriptor(1, descriptors, &_presentation));
        RETURN_IF_FAILED(MFCreateEventQueue(&_queue));
        return S_OK;
    }

    // IMFMediaEventGenerator
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) override {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        return _queue ? _queue->BeginGetEvent(callback, state) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) override {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        return _queue ? _queue->EndGetEvent(result, event) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override {
        ComPtr<IMFMediaEventQueue> queue;
        {
            std::lock_guard<std::recursive_mutex> guard(_lock);
            queue = _queue;
        }
        return queue ? queue->GetEvent(flags, event) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP QueueEvent(MediaEventType type, REFGUID extended, HRESULT status, const PROPVARIANT* value) override {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        return _queue ? _queue->QueueEventParamVar(type, extended, status, value) : MF_E_SHUTDOWN;
    }

    // IMFMediaSource
    STDMETHODIMP CreatePresentationDescriptor(IMFPresentationDescriptor** presentation) override {
        if (!presentation) return E_POINTER;
        std::lock_guard<std::recursive_mutex> guard(_lock);
        return _presentation ? _presentation->Clone(presentation) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP GetCharacteristics(DWORD* characteristics) override {
        if (!characteristics) return E_POINTER;
        *characteristics = MFMEDIASOURCE_IS_LIVE;
        return S_OK;
    }
    STDMETHODIMP Pause() override { return MF_E_INVALID_STATE_TRANSITION; }
    STDMETHODIMP Shutdown() override {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        if (!_queue) return MF_E_SHUTDOWN;
        _queue->Shutdown();
        _queue.Reset();
        if (_stream) _stream->Shutdown();
        _presentation.Reset();
        return S_OK;
    }
    STDMETHODIMP Start(IMFPresentationDescriptor* presentation, const GUID* timeFormat, const PROPVARIANT* startPosition) override {
        if (!presentation || !startPosition) return E_POINTER;
        if (timeFormat && *timeFormat != GUID_NULL) return MF_E_UNSUPPORTED_TIME_FORMAT;
        std::lock_guard<std::recursive_mutex> guard(_lock);
        if (!_queue || !_presentation) return MF_E_SHUTDOWN;

        DWORD count = 0;
        RETURN_IF_FAILED(presentation->GetStreamDescriptorCount(&count));
        if (count != 1) return E_INVALIDARG;
        BOOL selected = FALSE;
        ComPtr<IMFStreamDescriptor> descriptor;
        RETURN_IF_FAILED(presentation->GetStreamDescriptorByIndex(0, &selected, &descriptor));

        bool running = _stream->State() == MF_STREAM_STATE_RUNNING;
        if (selected && !running) {
            RETURN_IF_FAILED(_presentation->SelectStream(0));
            ComPtr<IUnknown> unknown;
            RETURN_IF_FAILED(_stream.As(&unknown));
            RETURN_IF_FAILED(_queue->QueueEventParamUnk(MENewStream, GUID_NULL, S_OK, unknown.Get()));
            ComPtr<IMFMediaTypeHandler> handler;
            ComPtr<IMFMediaType> type;
            RETURN_IF_FAILED(descriptor->GetMediaTypeHandler(&handler));
            RETURN_IF_FAILED(handler->GetCurrentMediaType(&type));
            RETURN_IF_FAILED(_stream->Start(type.Get()));
        } else if (!selected && running) {
            RETURN_IF_FAILED(_presentation->DeselectStream(0));
            RETURN_IF_FAILED(_stream->Stop());
        }

        PROPVARIANT time;
        RETURN_IF_FAILED(InitPropVariantFromInt64(MFGetSystemTime(), &time));
        HRESULT hr = _queue->QueueEventParamVar(MESourceStarted, GUID_NULL, S_OK, &time);
        PropVariantClear(&time);
        return hr;
    }
    STDMETHODIMP Stop() override {
        std::lock_guard<std::recursive_mutex> guard(_lock);
        if (!_queue || !_presentation) return MF_E_SHUTDOWN;
        if (_stream->State() != MF_STREAM_STATE_STOPPED) RETURN_IF_FAILED(_stream->Stop());
        _presentation->DeselectStream(0);
        PROPVARIANT time;
        RETURN_IF_FAILED(InitPropVariantFromInt64(MFGetSystemTime(), &time));
        HRESULT hr = _queue->QueueEventParamVar(MESourceStopped, GUID_NULL, S_OK, &time);
        PropVariantClear(&time);
        return hr;
    }

    // IMFMediaSourceEx
    STDMETHODIMP GetSourceAttributes(IMFAttributes** attributes) override {
        if (!attributes) return E_POINTER;
        return _attributes.CopyTo(attributes);
    }
    STDMETHODIMP GetStreamAttributes(DWORD id, IMFAttributes** attributes) override {
        if (!attributes) return E_POINTER;
        *attributes = nullptr;
        if (id != 0 || !_stream) return MF_E_INVALIDSTREAMNUMBER;
        IMFAttributes* streamAttributes = _stream->Attributes();
        streamAttributes->AddRef();
        *attributes = streamAttributes;
        return S_OK;
    }
    STDMETHODIMP SetD3DManager(IUnknown*) override {
        return S_OK; // CPU frames only: we just copy NV12 bytes
    }

    // IMFGetService
    STDMETHODIMP GetService(REFGUID, REFIID, LPVOID*) override { return MF_E_UNSUPPORTED_SERVICE; }

    // IMFSampleAllocatorControl
    STDMETHODIMP SetDefaultAllocator(DWORD id, IUnknown* allocator) override {
        if (!allocator) return E_POINTER;
        if (id != 0 || !_stream) return MF_E_INVALIDSTREAMNUMBER;
        return _stream->SetAllocator(allocator);
    }
    STDMETHODIMP GetAllocatorUsage(DWORD id, DWORD* inputId, MFSampleAllocatorUsage* usage) override {
        if (!inputId || !usage) return E_POINTER;
        if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
        *inputId = id;
        *usage = MFSampleAllocatorUsage_UsesProvidedAllocator;
        return S_OK;
    }

    // IKsControl
    STDMETHODIMP KsProperty(PKSPROPERTY, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }
    STDMETHODIMP KsMethod(PKSMETHOD, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }
    STDMETHODIMP KsEvent(PKSEVENT, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }

private:
    std::recursive_mutex _lock;
    ComPtr<IMFAttributes> _attributes;
    ComPtr<IMFMediaEventQueue> _queue;
    ComPtr<IMFPresentationDescriptor> _presentation;
    ComPtr<MediaStream> _stream;
};

// ---------------------------------------------------------------------------------------------
// Activator: what the registered CLSID creates. IMFActivate is an IMFAttributes, so it forwards
// all attribute calls to an inner store.
// ---------------------------------------------------------------------------------------------
#define FORWARD(name, params, args) STDMETHODIMP name params override { return _attributes->name args; }

class Activator : public RuntimeClass<RuntimeClassFlags<ClassicCom>, ChainInterfaces<IMFActivate, IMFAttributes>> {
public:
    HRESULT Initialize() {
        RETURN_IF_FAILED(MFCreateAttributes(&_attributes, 4));
        RETURN_IF_FAILED(_attributes->SetUINT32(MF_VIRTUALCAMERA_PROVIDE_ASSOCIATED_CAMERA_SOURCES, 1));
        RETURN_IF_FAILED(_attributes->SetGUID(MFT_TRANSFORM_CLSID_Attribute, CLSID_PhoneMicVCam));
        return S_OK;
    }

    // IMFActivate
    STDMETHODIMP ActivateObject(REFIID riid, void** result) override {
        if (!result) return E_POINTER;
        *result = nullptr;
        std::lock_guard<std::mutex> guard(_lock);
        if (!_source) {
            auto source = Make<MediaSource>();
            if (!source) return E_OUTOFMEMORY;
            RETURN_IF_FAILED(source->Initialize(_attributes.Get()));
            _source = source;
        }
        return _source.CopyTo(riid, result);
    }
    STDMETHODIMP ShutdownObject() override {
        std::lock_guard<std::mutex> guard(_lock);
        if (_source) _source->Shutdown();
        return S_OK;
    }
    STDMETHODIMP DetachObject() override {
        std::lock_guard<std::mutex> guard(_lock);
        _source.Reset();
        return S_OK;
    }

    // IMFAttributes
    FORWARD(GetItem, (REFGUID k, PROPVARIANT* v), (k, v))
    FORWARD(GetItemType, (REFGUID k, MF_ATTRIBUTE_TYPE* t), (k, t))
    FORWARD(CompareItem, (REFGUID k, REFPROPVARIANT v, BOOL* r), (k, v, r))
    FORWARD(Compare, (IMFAttributes* a, MF_ATTRIBUTES_MATCH_TYPE m, BOOL* r), (a, m, r))
    FORWARD(GetUINT32, (REFGUID k, UINT32* v), (k, v))
    FORWARD(GetUINT64, (REFGUID k, UINT64* v), (k, v))
    FORWARD(GetDouble, (REFGUID k, double* v), (k, v))
    FORWARD(GetGUID, (REFGUID k, GUID* v), (k, v))
    FORWARD(GetStringLength, (REFGUID k, UINT32* l), (k, l))
    FORWARD(GetString, (REFGUID k, LPWSTR v, UINT32 s, UINT32* l), (k, v, s, l))
    FORWARD(GetAllocatedString, (REFGUID k, LPWSTR* v, UINT32* l), (k, v, l))
    FORWARD(GetBlobSize, (REFGUID k, UINT32* s), (k, s))
    FORWARD(GetBlob, (REFGUID k, UINT8* b, UINT32 s, UINT32* l), (k, b, s, l))
    FORWARD(GetAllocatedBlob, (REFGUID k, UINT8** b, UINT32* s), (k, b, s))
    FORWARD(GetUnknown, (REFGUID k, REFIID r, LPVOID* v), (k, r, v))
    FORWARD(SetItem, (REFGUID k, REFPROPVARIANT v), (k, v))
    FORWARD(DeleteItem, (REFGUID k), (k))
    FORWARD(DeleteAllItems, (), ())
    FORWARD(SetUINT32, (REFGUID k, UINT32 v), (k, v))
    FORWARD(SetUINT64, (REFGUID k, UINT64 v), (k, v))
    FORWARD(SetDouble, (REFGUID k, double v), (k, v))
    FORWARD(SetGUID, (REFGUID k, REFGUID v), (k, v))
    FORWARD(SetString, (REFGUID k, LPCWSTR v), (k, v))
    FORWARD(SetBlob, (REFGUID k, const UINT8* b, UINT32 s), (k, b, s))
    FORWARD(SetUnknown, (REFGUID k, IUnknown* v), (k, v))
    FORWARD(LockStore, (), ())
    FORWARD(UnlockStore, (), ())
    FORWARD(GetCount, (UINT32* c), (c))
    FORWARD(GetItemByIndex, (UINT32 i, GUID* k, PROPVARIANT* v), (i, k, v))
    FORWARD(CopyAllItems, (IMFAttributes* d), (d))

private:
    std::mutex _lock;
    ComPtr<IMFAttributes> _attributes;
    ComPtr<MediaSource> _source;
};

class ClassFactory : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IClassFactory> {
public:
    STDMETHODIMP CreateInstance(IUnknown* outer, REFIID riid, void** result) override {
        if (!result) return E_POINTER;
        *result = nullptr;
        if (outer) return CLASS_E_NOAGGREGATION;
        auto activator = Make<Activator>();
        if (!activator) return E_OUTOFMEMORY;
        RETURN_IF_FAILED(activator->Initialize());
        return activator.CopyTo(riid, result);
    }
    STDMETHODIMP LockServer(BOOL) override { return S_OK; }
};

// ---------------------------------------------------------------------------------------------
// COM server entry points
// ---------------------------------------------------------------------------------------------
BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = module;
        DisableThreadLibraryCalls(module);
    }
    return TRUE;
}

STDAPI DllCanUnloadNow() { return S_FALSE; } // the Feed thread may still be winding down

STDAPI DllGetClassObject(REFCLSID clsid, REFIID riid, LPVOID* result) {
    if (!result) return E_POINTER;
    *result = nullptr;
    if (clsid != CLSID_PhoneMicVCam) return CLASS_E_CLASSNOTAVAILABLE;
    auto factory = Make<ClassFactory>();
    if (!factory) return E_OUTOFMEMORY;
    return factory.CopyTo(riid, result);
}

static std::wstring ClsidString() {
    wchar_t text[64] = {};
    StringFromGUID2(CLSID_PhoneMicVCam, text, 64);
    return text;
}

// The Frame Server runs as another account, so registration must be machine-wide (HKLM).
STDAPI DllRegisterServer() {
    wchar_t path[MAX_PATH] = {};
    if (!GetModuleFileNameW(g_module, path, MAX_PATH)) return HRESULT_FROM_WIN32(GetLastError());
    std::wstring keyPath = L"Software\\Classes\\CLSID\\" + ClsidString() + L"\\InprocServer32";
    HKEY key = nullptr;
    LSTATUS status = RegCreateKeyExW(HKEY_LOCAL_MACHINE, keyPath.c_str(), 0, nullptr, 0, KEY_WRITE, nullptr, &key, nullptr);
    if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
    status = RegSetValueExW(key, nullptr, 0, REG_SZ, (const BYTE*)path, (DWORD)((wcslen(path) + 1) * sizeof(wchar_t)));
    static const wchar_t threading[] = L"Both";
    if (status == ERROR_SUCCESS) status = RegSetValueExW(key, L"ThreadingModel", 0, REG_SZ, (const BYTE*)threading, sizeof(threading));
    RegCloseKey(key);
    return HRESULT_FROM_WIN32(status);
}

STDAPI DllUnregisterServer() {
    std::wstring keyPath = L"Software\\Classes\\CLSID\\" + ClsidString();
    LSTATUS status = RegDeleteTreeW(HKEY_LOCAL_MACHINE, keyPath.c_str());
    return status == ERROR_FILE_NOT_FOUND ? S_OK : HRESULT_FROM_WIN32(status);
}

// ---------------------------------------------------------------------------------------------
// Functions the desktop app calls (through JNA), always from one dedicated thread.
// ---------------------------------------------------------------------------------------------
static ComPtr<IMFVirtualCamera> g_camera;
static bool g_started = false;

static HRESULT EnsureStartup() {
    if (g_started) return S_OK;
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE) return hr;
    RETURN_IF_FAILED(MFStartup(MF_VERSION));
    g_started = true;
    return S_OK;
}

// Registers "Phone Mic Camera" for this user until PhoneMicVCamStop() or until the app exits.
extern "C" __declspec(dllexport) HRESULT __stdcall PhoneMicVCamStart(const wchar_t* friendlyName) {
    RETURN_IF_FAILED(EnsureStartup());
    if (g_camera) return S_OK;
    std::wstring clsid = ClsidString();
    ComPtr<IMFVirtualCamera> camera;
    RETURN_IF_FAILED(MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource, MFVirtualCameraLifetime_Session,
                                           MFVirtualCameraAccess_CurrentUser, friendlyName, clsid.c_str(), nullptr, 0, &camera));
    RETURN_IF_FAILED(camera->Start(nullptr));
    g_camera = camera;
    return S_OK;
}

extern "C" __declspec(dllexport) HRESULT __stdcall PhoneMicVCamStop() {
    if (!g_camera) return S_OK;
    HRESULT hr = g_camera->Remove();
    g_camera->Shutdown();
    g_camera.Reset();
    return hr;
}

// Self-test: opens the camera named friendlyName like any app would, reads `frames` frames and
// reports the last one's size and average luma. Windows lists the camera as
// "<friendlyName> (Windows Virtual Camera)", so this matches on the prefix.
extern "C" __declspec(dllexport) HRESULT __stdcall PhoneMicVCamProbe(const wchar_t* friendlyName, int frames, int* width, int* height, int* averageLuma) {
    if (!width || !height || !averageLuma) return E_POINTER;
    RETURN_IF_FAILED(EnsureStartup());
    ComPtr<IMFAttributes> query;
    RETURN_IF_FAILED(MFCreateAttributes(&query, 1));
    RETURN_IF_FAILED(query->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID));
    IMFActivate** devices = nullptr;
    UINT32 count = 0;
    RETURN_IF_FAILED(MFEnumDeviceSources(query.Get(), &devices, &count));
    ComPtr<IMFActivate> match;
    for (UINT32 i = 0; i < count; i++) {
        wchar_t* name = nullptr;
        UINT32 length = 0;
        if (!match && SUCCEEDED(devices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &name, &length)) && wcsncmp(name, friendlyName, wcslen(friendlyName)) == 0) {
            match = devices[i];
        }
        if (name) CoTaskMemFree(name);
        devices[i]->Release();
    }
    CoTaskMemFree(devices);
    if (!match) return HRESULT_FROM_WIN32(ERROR_NOT_FOUND);

    ComPtr<IMFMediaSource> source;
    RETURN_IF_FAILED(match->ActivateObject(IID_PPV_ARGS(&source)));
    ComPtr<IMFSourceReader> reader;
    HRESULT hr = MFCreateSourceReaderFromMediaSource(source.Get(), nullptr, &reader);
    if (SUCCEEDED(hr)) {
        ComPtr<IMFMediaType> type;
        hr = reader->GetCurrentMediaType((DWORD)MF_SOURCE_READER_FIRST_VIDEO_STREAM, &type);
        UINT32 w = 0, h = 0;
        if (SUCCEEDED(hr)) hr = MFGetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, &w, &h);
        ComPtr<IMFSample> last;
        for (int i = 0; SUCCEEDED(hr) && i < frames; i++) {
            DWORD stream = 0, flags = 0;
            LONGLONG timestamp = 0;
            ComPtr<IMFSample> sample;
            hr = reader->ReadSample((DWORD)MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, &stream, &flags, &timestamp, &sample);
            if (sample) last = sample;
        }
        if (SUCCEEDED(hr) && !last) hr = MF_E_END_OF_STREAM;
        if (SUCCEEDED(hr)) {
            ComPtr<IMFMediaBuffer> buffer;
            hr = last->ConvertToContiguousBuffer(&buffer);
            BYTE* data = nullptr;
            DWORD length = 0;
            if (SUCCEEDED(hr)) hr = buffer->Lock(&data, nullptr, &length);
            if (SUCCEEDED(hr)) {
                UINT64 sum = 0, samples = 0;
                DWORD lumaBytes = std::min<DWORD>(length, w * h);
                for (DWORD i = 0; i < lumaBytes; i += 7) {
                    sum += data[i];
                    samples++;
                }
                buffer->Unlock();
                *width = (int)w;
                *height = (int)h;
                *averageLuma = samples ? (int)(sum / samples) : 0;
            }
        }
    }
    source->Shutdown();
    return hr;
}
