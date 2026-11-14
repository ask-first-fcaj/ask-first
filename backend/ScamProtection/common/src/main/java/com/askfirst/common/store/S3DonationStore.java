package com.askfirst.common.store;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Lưu trữ và xoá các mẫu tin đóng góp tại thư mục {@code donated/<hash(uid)>/} trên Amazon S3.
 */
public final class S3DonationStore implements DonationStore {

    private final S3Client s3;
    private final String bucketName;

    public S3DonationStore(S3Client s3, String bucketName) {
        this.s3 = Objects.requireNonNull(s3, "s3 client must not be null");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName must not be null");
    }

    public static String hashUid(String uid) {
        return AuditRepo.hashUid(uid);
    }

    @Override
    public String saveDonatedSample(String userId, String checkId, String maskedText) {
        String uidHash = hashUid(userId);
        String sampleId = (checkId != null && !checkId.isBlank()) ? checkId : java.util.UUID.randomUUID().toString();
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

    @Override
    public void deleteDonatedSamples(String userId) {
        String uidHash = hashUid(userId);
        String prefix = "donated/" + uidHash + "/";
        try {
            ListObjectsV2Response listRes = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build());
            for (S3Object obj : listRes.contents()) {
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(obj.key())
                        .build());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean hasDonatedSamples(String userId) {
        String uidHash = hashUid(userId);
        String prefix = "donated/" + uidHash + "/";
        try {
            ListObjectsV2Response listRes = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .maxKeys(1)
                    .build());
            return !listRes.contents().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
