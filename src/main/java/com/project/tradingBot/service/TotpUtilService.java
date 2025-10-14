package com.project.tradingBot.service;

import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class TotpUtilService {

    private static final String HMAC_ALGO = "HmacSHA1";
    private static final int TIME_STEP = 30; // 30 sec interval
    private static final int DIGITS = 6;

    /**
     * Generate TOTP using Base32 secret (same as Google Authenticator).
     */
    public String generateTotp(String base32Secret) {
        try {
            Base32 base32 = new Base32();
            byte[] secretBytes = base32.decode(base32Secret);

            long timeIndex = System.currentTimeMillis() / 1000 / TIME_STEP;
            byte[] timeBytes = Hex.decodeHex(String.format("%016x", timeIndex).toCharArray());

            SecretKeySpec signKey = new SecretKeySpec(secretBytes, HMAC_ALGO);
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(signKey);
            byte[] hash = mac.doFinal(timeBytes);

            int offset = hash[hash.length - 1] & 0xf;
            int binary =
                    ((hash[offset] & 0x7f) << 24) |
                            ((hash[offset + 1] & 0xff) << 16) |
                            ((hash[offset + 2] & 0xff) << 8) |
                            (hash[offset + 3] & 0xff);

            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new RuntimeException("Error generating TOTP", e);
        }
    }
}
