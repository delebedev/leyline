// src/native/arena-shim.c
//
// Single C shim for arena-ts. Compiled to dylib, called via Bun FFI.
// Provides: accessibility check, MTGA window bounds, mouse input.
//
// Build: cc -shared -O2 -o arena-shim.dylib arena-shim.c \
//        -framework CoreGraphics -framework ApplicationServices

#include <CoreGraphics/CoreGraphics.h>
#include <ApplicationServices/ApplicationServices.h>
#include <mach/mach_time.h>
#include <string.h>
#include <unistd.h>

// --- Accessibility ---

int shim_check_accessibility(void) {
    return (int)CGPreflightPostEventAccess();
}

// --- Window bounds ---
// Returns 1 if MTGA found, writes bounds to out params.
// No accessibility needed — CGWindowList is public API.

int shim_find_mtga(double *x, double *y, double *w, double *h) {
    CFArrayRef list = CGWindowListCopyWindowInfo(
        kCGWindowListOptionOnScreenOnly | kCGWindowListExcludeDesktopElements,
        kCGNullWindowID);
    if (!list) return 0;

    int found = 0;
    CFIndex count = CFArrayGetCount(list);
    for (CFIndex i = 0; i < count; i++) {
        CFDictionaryRef entry = CFArrayGetValueAtIndex(list, i);
        CFStringRef owner;
        if (!CFDictionaryGetValueIfPresent(entry, kCGWindowOwnerName, (const void **)&owner))
            continue;
        char buf[256];
        CFStringGetCString(owner, buf, sizeof(buf), kCFStringEncodingUTF8);
        if (strcmp(buf, "MTGA") != 0) continue;

        CFDictionaryRef bounds;
        if (CFDictionaryGetValueIfPresent(entry, kCGWindowBounds, (const void **)&bounds)) {
            CGRect rect;
            CGRectMakeWithDictionaryRepresentation(bounds, &rect);
            *x = rect.origin.x;
            *y = rect.origin.y;
            *w = rect.size.width;
            *h = rect.size.height;
            found = 1;
        }
        break;
    }
    CFRelease(list);
    return found;
}

// --- Mouse input ---
// All events: mach_absolute_time() timestamp + kCGHIDEventTap (Sequoia-safe).

static void post(CGEventRef event) {
    if (!event) return;
    CGEventSetTimestamp(event, mach_absolute_time());
    CGEventPost(kCGHIDEventTap, event);
    CFRelease(event);
}

void shim_click(double x, double y) {
    CGPoint p = CGPointMake(x, y);
    post(CGEventCreateMouseEvent(NULL, kCGEventLeftMouseDown, p, kCGMouseButtonLeft));
    usleep(50000); // 50ms hold
    post(CGEventCreateMouseEvent(NULL, kCGEventLeftMouseUp, p, kCGMouseButtonLeft));
}

void shim_move(double x, double y) {
    CGPoint p = CGPointMake(x, y);
    post(CGEventCreateMouseEvent(NULL, kCGEventMouseMoved, p, kCGMouseButtonLeft));
}

void shim_mouse_down(double x, double y) {
    CGPoint p = CGPointMake(x, y);
    post(CGEventCreateMouseEvent(NULL, kCGEventLeftMouseDown, p, kCGMouseButtonLeft));
}

void shim_mouse_up(double x, double y) {
    CGPoint p = CGPointMake(x, y);
    post(CGEventCreateMouseEvent(NULL, kCGEventLeftMouseUp, p, kCGMouseButtonLeft));
}

void shim_right_click(double x, double y) {
    CGPoint p = CGPointMake(x, y);
    post(CGEventCreateMouseEvent(NULL, kCGEventRightMouseDown, p, kCGMouseButtonRight));
    usleep(50000);
    post(CGEventCreateMouseEvent(NULL, kCGEventRightMouseUp, p, kCGMouseButtonRight));
}
