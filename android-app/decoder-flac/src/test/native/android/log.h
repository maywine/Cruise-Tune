#pragma once

#include <cstdarg>
#include <cstdio>
#include <cstdlib>

enum { ANDROID_LOG_VERBOSE = 2, ANDROID_LOG_ERROR = 6 };

inline int __android_log_print(int priority, const char* tag, const char* format, ...) {
    if (priority < ANDROID_LOG_ERROR) return 0;
    std::fprintf(stderr, "%s: ", tag);
    va_list args;
    va_start(args, format);
    int result = std::vfprintf(stderr, format, args);
    va_end(args);
    std::fputc('\n', stderr);
    return result;
}

[[noreturn]] inline void __android_log_assert(const char*, const char* tag, const char* format, ...) {
    std::fprintf(stderr, "%s: %s\n", tag, format);
    std::abort();
}
