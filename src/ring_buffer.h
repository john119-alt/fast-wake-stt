// RingBuffer<T> — fixed-capacity circular audio buffer.
//
// IMPROVEMENT #2 over Ordi's engine: Ordi's ordi_wake_feed() accumulated audio
// into a std::vector<float> that it reallocated (operator_new + memcpy +
// operator_delete) every time it grew — on the always-on hot path. This buffer
// allocates ONCE and never reallocates: feeds are O(n) copies with no malloc.
#pragma once
#include <cstddef>
#include <vector>
#include <algorithm>

template <typename T>
class RingBuffer {
public:
    explicit RingBuffer(size_t capacity) : buf_(capacity), cap_(capacity) {}

    // Append n samples; if full, oldest samples are overwritten (sliding window).
    void push(const T* data, size_t n) {
        if (n >= cap_) { // only the last cap_ samples matter
            std::copy(data + (n - cap_), data + n, buf_.begin());
            head_ = 0; size_ = cap_; return;
        }
        for (size_t i = 0; i < n; ++i) {
            buf_[(head_ + size_) % cap_] = data[i];
            if (size_ < cap_) ++size_;
            else head_ = (head_ + 1) % cap_;
        }
    }

    // Copy the current window out in order (for a transcription pass).
    void snapshot(std::vector<T>& out) const {
        out.resize(size_);
        for (size_t i = 0; i < size_; ++i) out[i] = buf_[(head_ + i) % cap_];
    }

    size_t size() const { return size_; }
    size_t capacity() const { return cap_; }
    void clear() { head_ = 0; size_ = 0; }

private:
    std::vector<T> buf_;
    size_t cap_, head_ = 0, size_ = 0;
};
