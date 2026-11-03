#include "ic_preflight.h"
#include <string.h>

struct bits { const unsigned char *p; size_t n, bit; int bad; };
static unsigned bits(struct bits *b, unsigned n)
{
    unsigned v = 0, i;
    if (n > 32 || b->bit > b->n * 8 || n > b->n * 8 - b->bit) {
        b->bad = 1; return 0;
    }
    for (i = 0; i < n; ++i, ++b->bit)
        v = (v << 1) | ((b->p[b->bit / 8] >> (7 - b->bit % 8)) & 1);
    return v;
}
static unsigned ue(struct bits *b)
{
    unsigned z = 0;
    while (!b->bad && !bits(b, 1)) if (++z > 24) { b->bad = 1; return 0; }
    return ((1u << z) - 1) + bits(b, z);
}
/* All consumers only compare signed Exp-Golomb codes for equality or skip them. */
static unsigned se_code(struct bits *b) { return ue(b); }
static int unescape(const unsigned char *p, size_t n, unsigned char *out, size_t cap, size_t *len)
{
    size_t i, used = 0;
    unsigned zeros = 0;
    for (i = 0; i < n; ++i) {
        unsigned c = p[i];
        if (zeros == 2 && c == 3) {
            if (i + 1 == n || p[i + 1] > 3) return -1;
            zeros = 0; continue;
        }
        if (zeros == 2 && c < 3) return -1;
        if (used == cap) return -1;
        out[used++] = (unsigned char)c;
        zeros = c == 0 ? zeros + 1 : 0;
    }
    *len = used; return 0;
}
static int trailing(struct bits *b)
{
    if (bits(b, 1) != 1) return -1;
    while (!b->bad && b->bit % 8) if (bits(b, 1)) return -1;
    return b->bad || b->bit != b->n * 8 ? -1 : 0;
}
static int hrd(struct bits *b)
{
    unsigned count = ue(b), i;
    if (count > 31) return -1;
    (void)bits(b, 4); (void)bits(b, 4);
    for (i = 0; i <= count; ++i) { (void)ue(b); (void)ue(b); (void)bits(b, 1); }
    (void)bits(b, 5); (void)bits(b, 5); (void)bits(b, 5); (void)bits(b, 5);
    return b->bad ? -1 : 0;
}
static int vui(struct bits *b)
{
    unsigned nal_hrd, vcl_hrd;
    if (bits(b, 1) && bits(b, 8) == 255) { (void)bits(b, 16); (void)bits(b, 16); }
    if (bits(b, 1)) (void)bits(b, 1);
    if (bits(b, 1)) {
        (void)bits(b, 3); (void)bits(b, 1);
        if (bits(b, 1)) { (void)bits(b, 8); (void)bits(b, 8); (void)bits(b, 8); }
    }
    if (bits(b, 1)) { (void)ue(b); (void)ue(b); }
    if (bits(b, 1)) { (void)bits(b, 32); (void)bits(b, 32); (void)bits(b, 1); }
    nal_hrd = bits(b, 1); if (nal_hrd && hrd(b)) return -1;
    vcl_hrd = bits(b, 1); if (vcl_hrd && hrd(b)) return -1;
    if (nal_hrd || vcl_hrd) (void)bits(b, 1);
    (void)bits(b, 1);
    if (bits(b, 1)) {
        (void)bits(b, 1); (void)ue(b); (void)ue(b); (void)ue(b); (void)ue(b);
        if (ue(b) != 0 || ue(b) > 16) return -1; /* no reordered pictures */
    }
    return b->bad ? -1 : 0;
}
static int sps(struct ic_preflight *s, const unsigned char *p, size_t n)
{
    unsigned char rbsp[IC_PS_MAX];
    size_t len;
    struct bits b;
    unsigned wm, hm, l = 0, r = 0, t = 0, d = 0, i, count, constraints;
    if (!n || unescape(p, n, rbsp, sizeof(rbsp), &len)) return -1;
    b = (struct bits){rbsp, len, 0, 0};
    if (bits(&b, 8) != 66) return -1; /* Baseline implies 8-bit 4:2:0 */
    constraints = bits(&b, 8);
    if (constraints & 3) return -1;
    if (bits(&b, 8) > 31) return -1; /* <= level 3.1 */
    s->sps_id = ue(&b);
    if (s->sps_id > 31) return -1;
    s->frame_bits = ue(&b) + 4;
    if (s->frame_bits > 16) return -1;
    s->poc_type = ue(&b);
    if (s->poc_type == 0) {
        s->poc_bits = ue(&b) + 4;
        if (s->poc_bits > 16) return -1;
    } else if (s->poc_type == 1) {
        s->delta_always_zero = bits(&b, 1);
        (void)se_code(&b); (void)se_code(&b);
        count = ue(&b);
        if (count > 255) return -1;
        for (i = 0; i < count; ++i) (void)se_code(&b);
    } else if (s->poc_type != 2) return -1;
    if (ue(&b) > 16) return -1;
    if (bits(&b, 1)) return -1; /* no frame_num gaps */
    wm = ue(&b); hm = ue(&b);
    if (wm >= 80 || hm >= 45 || !bits(&b, 1)) return -1;
    (void)bits(&b, 1);
    if (bits(&b, 1)) { l = ue(&b); r = ue(&b); t = ue(&b); d = ue(&b); }
    if (bits(&b, 1) && vui(&b)) return -1;
    if (trailing(&b)) return -1;
    s->width = (wm + 1) * 16; s->height = (hm + 1) * 16;
    if (l > 640 || r > 640 || t > 360 || d > 360 ||
        2 * (l + r) >= s->width || 2 * (t + d) >= s->height) return -1;
    s->left = 2 * l; s->top = 2 * t;
    s->visible_width = s->width - 2 * (l + r);
    s->visible_height = s->height - 2 * (t + d);
    /* SDK rejects coded pictures below 64. */
    return s->width >= 64 && s->height >= 64 ? 0 : -1;
}
static int pps(struct ic_preflight *s, const unsigned char *p, size_t n)
{
    unsigned char rbsp[IC_PS_MAX];
    size_t len;
    struct bits b;
    if (!n || unescape(p, n, rbsp, sizeof(rbsp), &len)) return -1;
    b = (struct bits){rbsp, len, 0, 0};
    s->pps_id = ue(&b);
    if (s->pps_id > 255 || ue(&b) != s->sps_id || bits(&b, 1)) return -1;
    s->bottom_poc_present = bits(&b, 1);
    if (ue(&b) != 0) return -1; /* FMO unsupported */
    if (ue(&b) > 31 || ue(&b) > 31) return -1;
    (void)bits(&b, 1);
    if (bits(&b, 2)) return -1; /* weighted_bipred_idc is zero in baseline */
    (void)se_code(&b); (void)se_code(&b); (void)se_code(&b);
    (void)bits(&b, 1); (void)bits(&b, 1);
    if (bits(&b, 1)) return -1; /* redundant pictures unsupported */
    return trailing(&b); /* no high-profile PPS extension */
}
static size_t start(const unsigned char *p, size_t n, size_t at, size_t *sz)
{
    size_t i;
    for (i = at; i + 2 < n; ++i) if (!p[i] && !p[i + 1]) {
        if (p[i + 2] == 1) { *sz = 3; return i; }
        if (i + 3 < n && !p[i + 2] && p[i + 3] == 1) { *sz = 4; return i; }
    }
    return n;
}
struct picture { unsigned frame, idr, idr_id, ref, poc, delta0, delta1; };
static int slice(const struct ic_preflight *s, const unsigned char *p, size_t n,
                 unsigned header, struct picture *pic, unsigned *mb)
{
    /* Only the bounded slice header prefix is needed; payload stays in AU.
     * 256 unescaped bytes exceed the maximum accepted prefix here. */
    unsigned char rbsp[256];
    size_t len, prefix = n < 128 ? n : 128;
    struct bits b;
    unsigned kind;
    if (prefix < n && prefix >= 3 && p[prefix - 1] == 3 &&
        !p[prefix - 2] && !p[prefix - 3]) --prefix;
    if (unescape(p, prefix, rbsp, sizeof(rbsp), &len)) return -1;
    b = (struct bits){rbsp, len, 0, 0};
    *mb = ue(&b); kind = ue(&b);
    if (kind > 9 || (kind % 5 != 0 && kind % 5 != 2)) return -1;
    if ((header & 31) == 5 && kind % 5 != 2) return -1;
    if (ue(&b) != s->pps_id || *mb >= s->width * s->height / 256) return -1;
    memset(pic, 0, sizeof(*pic));
    pic->frame = bits(&b, s->frame_bits);
    pic->idr = (header & 31) == 5;
    pic->ref = (header >> 5) != 0;
    if (pic->idr) { pic->idr_id = ue(&b); if (pic->idr_id > 65535) return -1; }
    if (s->poc_type == 0) {
        pic->poc = bits(&b, s->poc_bits);
        if (s->bottom_poc_present) pic->delta0 = se_code(&b);
    } else if (s->poc_type == 1 && !s->delta_always_zero) {
        pic->delta0 = se_code(&b);
        if (s->bottom_poc_present) pic->delta1 = se_code(&b);
    }
    return b.bad ? -1 : 0;
}
int ic_preflight_au(struct ic_preflight *state, const unsigned char *p, size_t n)
{
    struct ic_preflight s;
    struct picture picture = {0}, current;
    size_t pos, z = 0, nextz = 0, begin, end, next, i;
    unsigned slices = 0, lastmb = 0, mb = 0, seen_sps = 0, seen_pps = 0;
    if (!state || !p || !n || n > IC_AU_MAX) return -1;
    s = *state;
    pos = start(p, n, 0, &z);
    if (pos == n) return -1;
    for (i = 0; i < pos; ++i) if (p[i]) return -1;
    while (pos < n) {
        unsigned h, type;
        begin = pos + z; next = start(p, n, begin, &nextz); end = next;
        while (end > begin && !p[end - 1]) --end;
        if (end <= begin + 1 || (p[begin] & 0x80)) return -1;
        h = p[begin]; type = h & 31;
        if (type == 7 || type == 8) {
            unsigned char *saved = type == 7 ? s.sps : s.pps;
            size_t *saved_len = type == 7 ? &s.sps_len : &s.pps_len;
            size_t count = end - begin;
            if (slices || !((h >> 5) & 3) || count > IC_PS_MAX) return -1;
            if (*saved_len) {
                if (*saved_len != count || memcmp(saved, p + begin, count)) return -1;
            } else {
                if (type == 8 && !s.sps_len) return -1;
                if ((type == 7 ? sps(&s, p + begin + 1, count - 1) :
                                 pps(&s, p + begin + 1, count - 1))) return -1;
                memcpy(saved, p + begin, count); *saved_len = count;
            }
            if (type == 7) seen_sps = 1; else seen_pps = 1;
        } else if (type == 1 || type == 5) {
            if (!s.sps_len || !s.pps_len ||
                (!s.ready && (!seen_sps || !seen_pps || type != 5)) ||
                (type == 5 && !(h & 0x60)) ||
                slice(&s, p + begin + 1, end - begin - 1, h, &current, &mb)) return -1;
            if (!slices) { if (mb) return -1; picture = current; }
            else if (mb <= lastmb || memcmp(&picture, &current, sizeof(picture))) return -1;
            ++slices; lastmb = mb;
        } else if (type == 9) {
            if (slices || (h & 0x60) || end - begin != 2 || (p[begin + 1] & 0x1f) != 0x10) return -1;
        } else if (type == 6 || type == 12) {
            if (h & 0x60) return -1;
        } else return -1; /* no EOS, partitioned, SVC/MVC or extension NALs */
        pos = next; z = nextz;
    }
    if (!slices) return -1;
    s.ready = 1; *state = s; return 0;
}
