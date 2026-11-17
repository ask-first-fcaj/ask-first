package com.askfirst.common.store;

import com.askfirst.common.Ulid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Lưu trữ các mẫu tin đóng góp vào thư mục {@code donated/<hash(uid)>/} trên Amazon S3.
 */
public final class S3DonationStore implements DonationStore {

    private final S3Client s3;
    private final String bucketName;

    public S3DonationStore(S3Client s3, String bucketName) {
        this.s3 = Objects.requireNonNull(s3, "s3 client must not be null");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName must not be null");
    }

    public static String hashUid(String uid) {
        if (uid == null) {
            uid = "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(uid.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public String saveDonatedSample(String userId, String checkId, String maskedText) {
        String uidHash = hashUid(userId);
        String sampleId = (checkId != null && !checkId.isBlank()) ? checkId : Ulid.next();
        String s3Key = "donated/" + uidHash + "/" + sampleId + ".txt";

        byte[] contentBytes = (maskedText != null ? maskedText : "").getBytes(StandardCharsets.UTF_8);

        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .contentType("text/plain; charset=utf-8")
                        .build(),
                RequestBody.fromBytes(contentBytes));

        return s3Key;
    }
}
