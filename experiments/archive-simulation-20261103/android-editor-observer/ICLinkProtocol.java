package com.xiaopeng.qa.camera;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** P7IC0001: bounded, authenticated session; all integers are big-endian. */
final class ICLinkProtocol {
    static final int MAX_AU = 1024 * 1024;
    static final int VIDEO = 1;
    private ICLinkProtocol() {}

    static byte[] token(String hex) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Instrument token must be 64 hex characters");
        byte[] b = new byte[32];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
    static void hello(OutputStream stream, int side, int[] crop, String key) throws IOException {
        if (side != 1 && side != 2) throw new IllegalArgumentException("side");
        validateCrop(crop);
        DataOutputStream out = new DataOutputStream(stream);
        out.writeBytes("P7IC0001"); out.write(token(key)); out.writeInt(side);
        for (int n : crop) out.writeInt(n);
        out.flush();
    }
    static void validateCrop(int[] c) {
        if (c == null || c.length != 4 || c[0] < 0 || c[1] < 0 || c[2] <= 0 || c[3] <= 0
                || c[0] > 1280 - c[2] || c[1] > 720 - c[3])
            throw new IllegalArgumentException("Crop must be inside the encoded 1280x720 frame");
    }
    static void video(OutputStream stream, byte[] au, long ptsUs) throws IOException {
        if (au.length == 0 || au.length > MAX_AU || ptsUs < 0) throw new IOException("invalid AU");
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(VIDEO); out.writeInt(au.length); out.writeLong(ptsUs); out.write(au); out.flush();
    }
    /**
     * Normalize complete four-byte-length AVCC first, otherwise complete Annex-B.
     * A prefix alone cannot distinguish them: AVCC lengths 256..511 start 00 00 01.
     * Framing validation is not H.264 syntax/profile validation (the receiver does that).
     */
    static byte[] annexB(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_AU)
            throw new IOException("NAL bounds");
        List<int[]> nals = avccNals(bytes);
        if (nals == null) nals = annexBNals(bytes);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int[] nal : nals) {
            int length = nal[1] - nal[0];
            if ((long)out.size() + 4 + length > MAX_AU) throw new IOException("AU too large");
            out.write(new byte[] {0,0,0,1}); out.write(bytes, nal[0], length);
        }
        return out.toByteArray();
    }
    private static boolean validHeader(byte[] bytes, int offset) {
        int h = bytes[offset] & 255, type = h & 31;
        return (h & 128) == 0 && type >= 1 && type <= 23;
    }
    /** Null means the ENTIRE buffer is not a valid four-byte-length AVCC sequence. */
    private static List<int[]> avccNals(byte[] bytes) {
        List<int[]> nals = new ArrayList<>();
        for (int p = 0; p < bytes.length;) {
            if (bytes.length - p < 4) return null;
            long n = ((bytes[p] & 255L) << 24) | ((bytes[p+1] & 255L) << 16)
                    | ((bytes[p+2] & 255L) << 8) | (bytes[p+3] & 255L);
            p += 4;
            if (n < 1 || n > bytes.length - p || !validHeader(bytes,p)) return null;
            int end = p + (int)n;
            // A length-delimited H.264 EBSP must not contain an unescaped start code.
            for (int i=p+1; i<end; i++) {
                int prefix = start(bytes,i);
                if (prefix != 0 && i + prefix <= end) return null;
            }
            nals.add(new int[]{p,end}); p=end;
        }
        return nals;
    }
    private static List<int[]> annexBNals(byte[] bytes) throws IOException {
        List<int[]> nals = new ArrayList<>();
        int p = 0;
        // Annex-B permits leading_zero_8bits, but never arbitrary leading garbage.
        while (p < bytes.length && start(bytes,p) == 0 && bytes[p] == 0) p++;
        if (p == bytes.length || start(bytes,p) == 0) throw new IOException("Annex-B prefix");
        while (p < bytes.length) {
            int begin = p + start(bytes,p), next = begin;
            while (next < bytes.length && start(bytes,next) == 0) next++;
            int end = next;
            // Strip trailing_zero_8bits, not bytes from the following start code.
            while (end > begin && bytes[end-1] == 0) end--;
            if (end <= begin || !validHeader(bytes,begin)) throw new IOException("Annex-B NAL header");
            nals.add(new int[]{begin,end}); p=next;
        }
        return nals;
    }
    /** Only these non-picture AUs may be ignored; unknown/unsupported video must fail closed. */
    static boolean isControlOnly(byte[] annexB) throws IOException {
        if (annexB == null || annexB.length == 0 || annexB.length > MAX_AU)
            throw new IOException("NAL bounds");
        for (int[] nal : annexBNals(annexB)) {
            int type = annexB[nal[0]] & 31;
            if (type != 6 && type != 7 && type != 8 && type != 9 && type != 12) return false;
        }
        return true;
    }
    private static int start(byte[] b, int i) {
        if (i + 3 <= b.length && b[i] == 0 && b[i+1] == 0 && b[i+2] == 1) return 3;
        if (i + 4 <= b.length && b[i] == 0 && b[i+1] == 0 && b[i+2] == 0 && b[i+3] == 1) return 4;
        return 0;
    }
    static boolean hasNal(byte[] b, int type) {
        for (int i = 0; i < b.length;) {
            int n = start(b, i);
            if (n != 0 && i + n < b.length) { if ((b[i+n] & 31) == type) return true; i += n; }
            else i++;
        }
        return false;
    }
    static byte[] concat(byte[] a, byte[] b) throws IOException {
        if ((long)a.length + b.length > MAX_AU) throw new IOException("AU too large");
        byte[] c = new byte[a.length+b.length]; System.arraycopy(a,0,c,0,a.length); System.arraycopy(b,0,c,a.length,b.length); return c;
    }
    /** Keep only SPS/PPS when assembling codec-specific data. */
    static byte[] config(byte[] b) throws IOException { return parameterSets(b, 0); }
    static byte[] mergeConfig(byte[] old, byte[] next) throws IOException {
        byte[] sps=parameterSets(next,7), pps=parameterSets(next,8);
        if(sps.length==0)sps=parameterSets(old,7);
        if(pps.length==0)pps=parameterSets(old,8);
        return concat(sps,pps);
    }
    private static byte[] parameterSets(byte[] b, int type) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int begin = -1, header = -1;
        for (int i = 0; i <= b.length;) {
            int n = i < b.length ? start(b,i) : 0;
            if (n != 0 || i == b.length) {
                if (begin >= 0 && header < b.length && ((b[header]&31)==7 || (b[header]&31)==8)
                        && (type==0 || (b[header]&31)==type)) out.write(b,begin,i-begin);
                if (i == b.length) break;
                begin=i; header=i+n; i+=n;
            } else i++;
        }
        return out.toByteArray();
    }
}
