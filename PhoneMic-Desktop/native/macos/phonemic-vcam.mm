// phonemic-vcam: feeds raw NV12 frames from stdin into OBS Studio's macOS camera extension
// ("OBS Virtual Camera", OBS 30+, macOS 13+), so Phone Mic's camera shows up as a webcam.
//
// Usage:   phonemic-vcam <width> <height> <fps>
// Input:   stdin, back-to-back NV12 frames of width*height*3/2 bytes, until EOF.
// Output:  prints "READY" once the sink stream is running.
// Exit:    0 normal (EOF / SIGTERM), 1 bad arguments, 2 OBS camera not found or not set up,
//          3 could not start the stream (e.g. OBS's own virtual camera is running).
//
// Built by build-dmg.sh into the app's lib/ folder; ObsMacCamera.kt drives it. Written from
// Apple's CoreMediaIO API docs and the extension's device UID; no OBS code is used.

#import <CoreMedia/CoreMedia.h>
#import <CoreMediaIO/CMIOHardware.h>
#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <vector>

static const char *kObsDeviceUID = "7626645E-4425-469E-9D8B-97E0FA59AC75";
static volatile sig_atomic_t gStop = 0;

static void onSignal(int) { gStop = 1; }

static CMIOObjectPropertyAddress propertyAddress(CMIOObjectPropertySelector selector) {
    return CMIOObjectPropertyAddress{selector, kCMIOObjectPropertyScopeGlobal, kCMIOObjectPropertyElementMain};
}

static std::vector<CMIOObjectID> objectList(CMIOObjectID owner, CMIOObjectPropertySelector selector) {
    CMIOObjectPropertyAddress address = propertyAddress(selector);
    UInt32 size = 0, used = 0;
    if (CMIOObjectGetPropertyDataSize(owner, &address, 0, NULL, &size) != kCMIOHardwareNoError || size == 0) return {};
    std::vector<CMIOObjectID> objects(size / sizeof(CMIOObjectID));
    if (CMIOObjectGetPropertyData(owner, &address, 0, NULL, size, &used, objects.data()) != kCMIOHardwareNoError) return {};
    objects.resize(used / sizeof(CMIOObjectID));
    return objects;
}

static CMIOObjectID findObsDevice() {
    CMIOObjectPropertyAddress uidAddress = propertyAddress(kCMIODevicePropertyDeviceUID);
    for (CMIOObjectID device : objectList(kCMIOObjectSystemObject, kCMIOHardwarePropertyDevices)) {
        CFStringRef uid = NULL;
        UInt32 used = 0;
        if (CMIOObjectGetPropertyData(device, &uidAddress, 0, NULL, sizeof(uid), &used, &uid) != kCMIOHardwareNoError || !uid) continue;
        char buffer[128];
        bool match = CFStringGetCString(uid, buffer, sizeof(buffer), kCFStringEncodingUTF8) && strcmp(buffer, kObsDeviceUID) == 0;
        CFRelease(uid);
        if (match) return device;
    }
    return kCMIOObjectUnknown;
}

/** The extension's sink (input) stream - the one clients push frames into. */
static CMIOStreamID findSinkStream(CMIOObjectID device) {
    std::vector<CMIOObjectID> streams = objectList(device, kCMIODevicePropertyStreams);
    CMIOObjectPropertyAddress directionAddress = propertyAddress(kCMIOStreamPropertyDirection);
    for (CMIOStreamID stream : streams) {
        UInt32 direction = 0, used = 0;
        if (CMIOObjectGetPropertyData(stream, &directionAddress, 0, NULL, sizeof(direction), &used, &direction) == kCMIOHardwareNoError &&
            direction == 1) {
            return stream;
        }
    }
    // OBS's extension exposes [source, sink]; fall back to the second one.
    return streams.size() >= 2 ? streams[1] : kCMIOObjectUnknown;
}

static bool readFully(uint8_t *buffer, size_t length) {
    size_t got = 0;
    while (got < length) {
        ssize_t r = read(STDIN_FILENO, buffer + got, length - got);
        if (r > 0) {
            got += (size_t)r;
        } else if (r < 0 && errno == EINTR && !gStop) {
            continue;
        } else {
            return false; // EOF, error or SIGTERM
        }
    }
    return true;
}

/** NV12 (Y plane + interleaved CbCr) -> UYVY ('2vuy': Cb Y0 Cr Y1), what OBS's extension expects. */
static void nv12ToUyvy(const uint8_t *nv12, int width, int height, uint8_t *dst, size_t dstStride) {
    const uint8_t *yPlane = nv12;
    const uint8_t *uvPlane = nv12 + (size_t)width * height;
    for (int y = 0; y < height; y++) {
        uint8_t *row = dst + (size_t)y * dstStride;
        const uint8_t *yRow = yPlane + (size_t)y * width;
        const uint8_t *uvRow = uvPlane + (size_t)(y / 2) * width;
        for (int x = 0; x < width; x += 2) {
            row[x * 2 + 0] = uvRow[x];     // Cb
            row[x * 2 + 1] = yRow[x];
            row[x * 2 + 2] = uvRow[x + 1]; // Cr
            row[x * 2 + 3] = yRow[x + 1];
        }
    }
}

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc != 4) {
            fprintf(stderr, "usage: phonemic-vcam <width> <height> <fps>\n");
            return 1;
        }
        const int width = atoi(argv[1]);
        const int height = atoi(argv[2]);
        const int fps = atoi(argv[3]);
        if (width <= 0 || height <= 0 || fps <= 0 || (width & 1) || (height & 1)) {
            fprintf(stderr, "phonemic-vcam: invalid size or fps\n");
            return 1;
        }

        signal(SIGTERM, onSignal);
        signal(SIGINT, onSignal);
        signal(SIGPIPE, SIG_IGN);

        CMIOObjectID device = findObsDevice();
        if (device == kCMIOObjectUnknown) {
            fprintf(stderr, "phonemic-vcam: OBS Virtual Camera not found (install OBS 30+, start and stop its virtual camera once)\n");
            return 2;
        }
        CMIOStreamID stream = findSinkStream(device);
        if (stream == kCMIOObjectUnknown) {
            fprintf(stderr, "phonemic-vcam: OBS Virtual Camera has no sink stream\n");
            return 2;
        }

        CMSimpleQueueRef queue = NULL;
        OSStatus status = CMIOStreamCopyBufferQueue(stream, [](CMIOStreamID, void *, void *) {}, NULL, &queue);
        if (status != kCMIOHardwareNoError || !queue) {
            fprintf(stderr, "phonemic-vcam: could not get the sink queue (%d)\n", (int)status);
            return 3;
        }
        status = CMIODeviceStartStream(device, stream);
        if (status != kCMIOHardwareNoError) {
            fprintf(stderr, "phonemic-vcam: could not start the stream (%d)\n", (int)status);
            CFRelease(queue);
            return 3;
        }

        printf("READY\n");
        fflush(stdout);

        const size_t frameBytes = (size_t)width * height * 3 / 2;
        std::vector<uint8_t> nv12(frameBytes);
        NSDictionary *attributes = @{(id)kCVPixelBufferIOSurfacePropertiesKey : @{}};
        CMVideoFormatDescriptionRef format = NULL;

        while (!gStop && readFully(nv12.data(), frameBytes)) {
            @autoreleasepool {
                CVPixelBufferRef pixels = NULL;
                if (CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_422YpCbCr8,
                                        (__bridge CFDictionaryRef)attributes, &pixels) != kCVReturnSuccess) {
                    break;
                }
                CVPixelBufferLockBaseAddress(pixels, 0);
                nv12ToUyvy(nv12.data(), width, height, (uint8_t *)CVPixelBufferGetBaseAddress(pixels),
                           CVPixelBufferGetBytesPerRow(pixels));
                CVPixelBufferUnlockBaseAddress(pixels, 0);

                if (!format) CMVideoFormatDescriptionCreateForImageBuffer(kCFAllocatorDefault, pixels, &format);

                CMSampleTimingInfo timing;
                timing.presentationTimeStamp = CMClockGetTime(CMClockGetHostTimeClock());
                timing.duration = CMTimeMake(1, fps);
                timing.decodeTimeStamp = kCMTimeInvalid;

                CMSampleBufferRef sample = NULL;
                if (format && CMSampleBufferCreateForImageBuffer(kCFAllocatorDefault, pixels, true, NULL, NULL, format,
                                                                 &timing, &sample) == noErr) {
                    // The consumer releases what it dequeues; if it's not keeping up, drop instead of queueing latency.
                    if (CMSimpleQueueGetCount(queue) < CMSimpleQueueGetCapacity(queue)) {
                        CMSimpleQueueEnqueue(queue, sample);
                    } else {
                        CFRelease(sample);
                    }
                }
                CVPixelBufferRelease(pixels);
            }
        }

        CMIODeviceStopStream(device, stream);
        if (format) CFRelease(format);
        CFRelease(queue);
        return 0;
    }
}
