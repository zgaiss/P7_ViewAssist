#include "ic_preflight.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
struct writer { unsigned char p[4096]; size_t bit; };
static void put(struct writer *w, unsigned n, unsigned v)
{
    while (n--) { if ((v >> n) & 1) w->p[w->bit / 8] |= (unsigned char)(1u << (7 - w->bit % 8)); ++w->bit; }
}
static void expg(struct writer *w, unsigned v)
{
    unsigned n = 0, x = ++v;
    while (x >>= 1) ++n;
    put(w, n, 0); put(w, n + 1, v);
}
static size_t nal(unsigned char *p, unsigned h, struct writer *w)
{
    size_t i, out = 5, n;
    unsigned zeros = 0;
    put(w, 1, 1); while (w->bit % 8) put(w, 1, 0);
    n = w->bit / 8;
    p[0] = p[1] = p[2] = 0; p[3] = 1; p[4] = (unsigned char)h;
    for (i = 0; i < n; ++i) {
        if (zeros == 2 && w->p[i] <= 3) { p[out++] = 3; zeros = 0; }
        p[out++] = w->p[i]; zeros = w->p[i] == 0 ? zeros + 1 : 0;
    }
    return out;
}
static size_t make_sps(unsigned char *p, unsigned profile, unsigned width, unsigned height, unsigned progressive)
{
    struct writer w = {{0}, 0};
    put(&w, 8, profile); put(&w, 8, 0xc0); put(&w, 8, 31);
    expg(&w, 0); expg(&w, 0); expg(&w, 2); expg(&w, 1); put(&w, 1, 0);
    expg(&w, width / 16 - 1); expg(&w, height / 16 - 1);
    put(&w, 1, progressive); put(&w, 1, 1); put(&w, 1, 0); put(&w, 1, 0);
    return nal(p, 0x67, &w);
}
static size_t make_pps(unsigned char *p, unsigned cabac)
{
    struct writer w = {{0}, 0};
    expg(&w, 0); expg(&w, 0); put(&w, 1, cabac); put(&w, 1, 0);
    expg(&w, 0); expg(&w, 0); expg(&w, 0); put(&w, 1, 0); put(&w, 2, 0);
    expg(&w, 0); expg(&w, 0); expg(&w, 0);
    put(&w, 1, 1); put(&w, 1, 0); put(&w, 1, 0);
    return nal(p, 0x68, &w);
}
static size_t make_slice(unsigned char *p, unsigned idr, unsigned kind, unsigned mb, unsigned frame)
{
    struct writer w = {{0}, 0};
    expg(&w, mb); expg(&w, kind); expg(&w, 0); put(&w, 4, frame);
    if (idr) expg(&w, 0);
    /* Artificial payload: parser validates headers, NOT entropy completeness. */
    put(&w, 8, 0x55);
    return nal(p, idr ? 0x65 : 0x41, &w);
}
static size_t first(unsigned char *p)
{
    size_t n = make_sps(p, 66, 1280, 720, 1);
    n += make_pps(p + n, 0); n += make_slice(p + n, 1, 2, 0, 0); return n;
}
int main(int argc, char **argv)
{
    unsigned char p[8192], q[8192];
    struct ic_preflight s = {0}, old;
    size_t n, m, i;
    assert(ic_preflight_au(NULL, p, 1) != 0);
    assert(ic_preflight_au(&s, NULL, 1) != 0);
    assert(ic_preflight_au(&s, p, 0) != 0);
    assert(ic_preflight_au(&s, p, IC_AU_MAX + 1) != 0);
    n = make_slice(p, 1, 2, 0, 0); assert(ic_preflight_au(&s, p, n) != 0);
    fputs("test: first AU\n", stderr);
    n = first(p); assert(ic_preflight_au(&s, p, n) == 0);
    assert(s.width == 1280 && s.height == 720 && s.ready);
    old = s;
    fputs("test: incremental AUs\n", stderr);
    for (i = 0; i < 10000; ++i) {
        m = make_slice(q, 0, 0, 0, (unsigned)i % 16);
        assert(ic_preflight_au(&s, q, m) == 0);
    }
    assert(ic_preflight_au(&s, p, n) == 0); /* identical repeated SPS/PPS */
    m = make_sps(q, 66, 640, 480, 1); m += make_slice(q + m, 1, 2, 0, 0);
    assert(ic_preflight_au(&s, q, m) != 0); assert(!memcmp(&s, &old, sizeof(s)));
    m = make_slice(q, 0, 1, 0, 1); assert(ic_preflight_au(&s, q, m) != 0); /* B */
    m = make_slice(q, 1, 0, 0, 0); assert(ic_preflight_au(&s, q, m) != 0); /* IDR P */
    m = make_slice(q, 0, 0, 1, 1); assert(ic_preflight_au(&s, q, m) != 0); /* missing first slice */
    m = make_slice(q, 0, 0, 0, 1); m += make_slice(q + m, 0, 0, 10, 1);
    assert(ic_preflight_au(&s, q, m) == 0); /* two slices one picture */
    m = make_slice(q, 0, 0, 0, 1); m += make_slice(q + m, 0, 0, 10, 2);
    assert(ic_preflight_au(&s, q, m) != 0); /* mixed picture AU */
    m = make_slice(q, 0, 0, 0, 1); m += make_slice(q + m, 0, 0, 0, 1);
    assert(ic_preflight_au(&s, q, m) != 0); /* two complete pictures */
    for (i = 0; i < n; ++i) {
        struct ic_preflight empty = {0};
        (void)ic_preflight_au(&empty, p, i); /* all truncations: bounds safety */
    }
    memset(&s, 0, sizeof(s));
    m = make_sps(q, 100, 1280, 720, 1); m += make_pps(q + m, 0); m += make_slice(q + m, 1, 2, 0, 0);
    assert(ic_preflight_au(&s, q, m) != 0); /* high profile */
    m = make_sps(q, 66, 1296, 720, 1); m += make_pps(q + m, 0); m += make_slice(q + m, 1, 2, 0, 0);
    assert(ic_preflight_au(&s, q, m) != 0);
    m = make_sps(q, 66, 1280, 736, 1); m += make_pps(q + m, 0); m += make_slice(q + m, 1, 2, 0, 0);
    assert(ic_preflight_au(&s, q, m) != 0);
    m = make_sps(q, 66, 1280, 720, 0); m += make_pps(q + m, 0); m += make_slice(q + m, 1, 2, 0, 0);
    assert(ic_preflight_au(&s, q, m) != 0);
    m = make_sps(q, 66, 1280, 720, 1); m += make_pps(q + m, 1); m += make_slice(q + m, 1, 2, 0, 0);
    assert(ic_preflight_au(&s, q, m) != 0);
    p[4] |= 0x80; assert(ic_preflight_au(&s, p, n) != 0); p[4] &= 0x7f;
    fputs("test: malformed tests completed; fuzzing\n", stderr);
    /* Deterministic random and stateful fuzz smoke test under ASan/UBSan. */
    {
        uint32_t r = 0x5eed1234;
        for (i = 0; i < 5000; ++i) {
            size_t j, len = i % sizeof(q);
            for (j = 0; j < len; ++j) { r ^= r << 13; r ^= r >> 17; r ^= r << 5; q[j] = (unsigned char)r; }
            if (len > 4) { q[0] = q[1] = q[2] = 0; q[3] = 1; }
            (void)ic_preflight_au(&s, q, len);
        }
    }
    if (argc == 2) {
        unsigned char stream[IC_AU_MAX];
        FILE *f = fopen(argv[1], "rb");
        size_t bytes, at, begin = 0, aus = 0;
        struct ic_preflight real = {0};
        assert(f); bytes = fread(stream, 1, sizeof(stream), f);
        assert(bytes && bytes < sizeof(stream) && !ferror(f)); fclose(f);
        /* x264 fixture uses aud=1; split only on its four-byte AUD starts. */
        for (at = 1; at + 4 < bytes; ++at) {
            if (!stream[at] && !stream[at + 1] && !stream[at + 2] &&
                stream[at + 3] == 1 && stream[at + 4] == 9) {
                assert(ic_preflight_au(&real, stream + begin, at - begin) == 0);
                ++aus; begin = at;
            }
        }
        assert(ic_preflight_au(&real, stream + begin, bytes - begin) == 0); ++aus;
        printf("real encoded fixture: %lu complete AUs accepted, %ux%u\n", (unsigned long)aus, real.visible_width, real.visible_height);
    }
    puts("preflight: PASS (10000 incremental AUs, malformed/changed/B/multi-picture bounds, 5000 fuzz inputs)");
    return 0;
}
