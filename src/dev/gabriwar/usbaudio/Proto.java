package dev.gabriwar.usbaudio;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wire protocol between the app and pc/bin/phone-relay for the Wi-Fi / Bluetooth links (USB uses
 * raw PCM over the adb tunnel instead). Pure Java on purpose: the PC test harness runs it too.
 * pc/bin/phone-relay implements the other end and must stay in sync with this file.
 *
 * Messages, first byte = type ("|" = concatenation, numbers big endian):
 *   HELLO   'H' | ver(1) | role(1) | flags(1) | sid(8) | X(256)       phone -> PC
 *   REPLY   'R' | sid(8) | Y(256) | pcConfirm(32)                     PC -> phone
 *   CONFIRM 'C' | sid(8) | phoneConfirm(32)                           phone -> PC
 *   OK      'O' | sid(8)                                              PC -> phone
 *   ERROR   'E' | sid(8) | code(1)                                    PC -> phone
 *   DATA    'D' | seq(4) | payload  (+16 byte GCM tag when encrypted)
 *   PING    'P'          keepalive (UDP), both ways
 *   BYE     'B'          phone is leaving
 *   WHO     'W'          phone looking for the PC (UDP broadcast); the PC answers 'w'
 *
 * On UDP one datagram = one message. On TCP (camera) and RFCOMM (Bluetooth direct) each message
 * is preceded by its length (u32).
 *
 * PIN check = SPAKE2 over the RFC 3526 2048-bit group: both sides prove they know the PIN and
 * agree on a fresh key, but someone recording the traffic can't brute-force the PIN offline
 * (each guess needs a live handshake, and the PC rate-limits those). Payload encryption is
 * AES-128-GCM (hardware AES on phones and PCs), one key per direction, nonce = 0^8 | seq.
 */
final class Proto {
    private Proto() {}

    static final int VERSION = 1;
    static final int ROLE_SPK = 1, ROLE_MIC = 2, ROLE_CAM = 3, ROLE_AUDIO = 4;
    static final int F_ENCRYPT = 1, F_OPUS = 2;
    static final int E_PIN = 1, E_DOWN = 2, E_UNKNOWN = 3, E_BUSY = 4, E_UNSUPPORTED = 5;
    static final int HELLO_LEN = 1 + 3 + 8 + 256, REPLY_LEN = 1 + 8 + 256 + 32, CONFIRM_LEN = 1 + 8 + 32;
    static final int TAG = 16, HDR = 5;

    static final BigInteger P = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A0879"
          + "8E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B"
          + "0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA4836"
          + "1C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804"
          + "F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6"
          + "955817183995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF", 16);
    static final BigInteger Q = P.shiftRight(1);
    static final BigInteger G = BigInteger.valueOf(4);               // generates the order-Q subgroup
    static final BigInteger M = hashToGroup("UAB-SPAKE2-M"), N = hashToGroup("UAB-SPAKE2-N");

    private static final SecureRandom RNG = new SecureRandom();

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Square of a 288-byte hash mod P: a subgroup element nobody knows the discrete log of. */
    private static BigInteger hashToGroup(String label) {
        byte[] wide = new byte[9 * 32];
        for (int i = 0; i < 9; i++) {
            System.arraycopy(sha256(ascii(label), new byte[]{(byte) i}), 0, wide, i * 32, 32);
        }
        return new BigInteger(1, wide).mod(P).modPow(BigInteger.valueOf(2), P);
    }

    static BigInteger pinScalar(String pin) {
        return new BigInteger(1, sha256(ascii("UAB-PIN"), ascii(pin))).mod(Q);
    }

    /** Fixed 256-byte big-endian encoding. */
    static byte[] i2b(BigInteger v) {
        byte[] raw = v.toByteArray(), out = new byte[256];
        int n = Math.min(raw.length, 256);
        System.arraycopy(raw, raw.length - n, out, 256 - n, n);
        return out;
    }

    static boolean inGroup(BigInteger v) {
        return v.compareTo(BigInteger.ONE) > 0 && v.compareTo(P.subtract(BigInteger.ONE)) < 0
                && v.modPow(Q, P).equals(BigInteger.ONE);
    }

    /** Phone side of one handshake. */
    static final class Client {
        final byte[] sid = new byte[8];
        final int role, flags;
        private final BigInteger w, x;
        private final byte[] X;
        byte[] sendKey, recvKey;            // after finish()

        Client(int role, int flags, String pin) {
            this.role = role;
            this.flags = flags;
            RNG.nextBytes(sid);
            w = pinScalar(pin);
            x = new BigInteger(256, RNG).add(BigInteger.ONE);
            X = i2b(G.modPow(x, P).multiply(M.modPow(w, P)).mod(P));
        }

        byte[] hello() {
            byte[] b = new byte[HELLO_LEN];
            b[0] = 'H';
            b[1] = VERSION;
            b[2] = (byte) role;
            b[3] = (byte) flags;
            System.arraycopy(sid, 0, b, 4, 8);
            System.arraycopy(X, 0, b, 12, 256);
            return b;
        }

        boolean sameSid(byte[] m, int off) {
            for (int i = 0; i < 8; i++) if (m[off + i] != sid[i]) return false;
            return true;
        }

        /** Checks the PC's REPLY; returns the CONFIRM to send, or null if the PC doesn't know the PIN. */
        byte[] finish(byte[] reply) {
            byte[] Yb = Arrays.copyOfRange(reply, 9, 9 + 256);
            BigInteger Y = new BigInteger(1, Yb);
            if (!inGroup(Y)) return null;
            BigInteger K = Y.multiply(N.modPow(Q.subtract(w).mod(Q), P)).mod(P).modPow(x, P);
            Keys k = new Keys(transcript(role, flags, sid, X, Yb), i2b(K), i2b(w));
            byte[] pcConfirm = Arrays.copyOfRange(reply, 9 + 256, 9 + 256 + 32);
            if (!MessageDigest.isEqual(pcConfirm, k.pcConfirm)) return null;
            sendKey = k.phoneToPc;
            recvKey = k.pcToPhone;
            byte[] c = new byte[CONFIRM_LEN];
            c[0] = 'C';
            System.arraycopy(sid, 0, c, 1, 8);
            System.arraycopy(k.phoneConfirm, 0, c, 9, 32);
            return c;
        }
    }

    static byte[] transcript(int role, int flags, byte[] sid, byte[] X, byte[] Y) {
        byte[] t = new byte[4 + 3 + 8 + 256 + 256];
        System.arraycopy(ascii("UAB1"), 0, t, 0, 4);
        t[4] = VERSION;
        t[5] = (byte) role;
        t[6] = (byte) flags;
        System.arraycopy(sid, 0, t, 7, 8);
        System.arraycopy(X, 0, t, 15, 256);
        System.arraycopy(Y, 0, t, 271, 256);
        return t;
    }

    static final class Keys {
        final byte[] phoneToPc, pcToPhone, pcConfirm, phoneConfirm;

        Keys(byte[] tt, byte[] K, byte[] w) {
            byte[] h = sha256(tt, K, w);
            phoneToPc = Arrays.copyOf(hmac(h, ascii("phone->pc")), 16);
            pcToPhone = Arrays.copyOf(hmac(h, ascii("pc->phone")), 16);
            pcConfirm = hmac(hmac(h, ascii("confirm pc")), tt);
            phoneConfirm = hmac(hmac(h, ascii("confirm phone")), tt);
        }
    }

    /** AES-128-GCM for one direction (or a pass-through when encryption is off). */
    static final class Seal {
        private final Cipher c;
        private final SecretKeySpec key;
        private final byte[] iv = new byte[12];
        final int overhead;

        Seal(byte[] key, boolean encrypt) {
            if (encrypt) {
                try {
                    c = Cipher.getInstance("AES/GCM/NoPadding");
                } catch (GeneralSecurityException e) {
                    throw new IllegalStateException(e);
                }
                this.key = new SecretKeySpec(key, "AES");
                overhead = TAG;
            } else {
                c = null;
                this.key = null;
                overhead = 0;
            }
        }

        private void nonce(int seq) {
            iv[8] = (byte) (seq >>> 24);
            iv[9] = (byte) (seq >>> 16);
            iv[10] = (byte) (seq >>> 8);
            iv[11] = (byte) seq;
        }

        /** Builds DATA at out[o..]; returns its length. */
        int seal(int seq, byte[] in, int off, int len, byte[] out, int o) {
            out[o] = 'D';
            out[o + 1] = (byte) (seq >>> 24);
            out[o + 2] = (byte) (seq >>> 16);
            out[o + 3] = (byte) (seq >>> 8);
            out[o + 4] = (byte) seq;
            if (c == null) {
                System.arraycopy(in, off, out, o + HDR, len);
                return HDR + len;
            }
            try {
                nonce(seq);
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
                c.updateAAD(out, o, HDR);
                return HDR + c.doFinal(in, off, len, out, o + HDR);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Opens the DATA message msg[o..o+len) into out; returns the payload length, -1 if forged/corrupt. */
        int open(byte[] msg, int o, int len, byte[] out) {
            int n = len - HDR;
            if (n < overhead) return -1;
            if (c == null) {
                System.arraycopy(msg, o + HDR, out, 0, n);
                return n;
            }
            try {
                nonce(seq(msg, o));
                c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                c.updateAAD(msg, o, HDR);
                return c.doFinal(msg, o + HDR, n, out, 0);
            } catch (GeneralSecurityException e) {
                return -1;
            }
        }
    }

    static int seq(byte[] m, int o) {
        return ((m[o + 1] & 0xff) << 24) | ((m[o + 2] & 0xff) << 16) | ((m[o + 3] & 0xff) << 8) | (m[o + 4] & 0xff);
    }
}
