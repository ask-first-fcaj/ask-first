package com.askfirst.common.store;

import java.util.List;
import java.util.Optional;

/**
 * Repository thao tác trên bản ghi CHECK# (kết quả và siêu dữ liệu kiểm tra).
 * Kế thừa {@link CheckStore} để tương thích hoàn toàn giữa Lane A và Lane B.
 */
public interface CheckRepo extends CheckStore {

    /** Lấy bản ghi kiểm tra theo userId và checkId. */
    Optional<CheckRecord> get(String userId, String checkId);

    /** Cập nhật trường feedback (ví dụ "wrong") cho bản ghi kiểm tra. */
    @Override
    void updateFeedback(String userId, String checkId, String feedback);

    /**
     * Trả về tối đa {@code limit} bản ghi CHECK# gần nhất của người dùng,
     * sắp xếp giảm dần theo SK.
     *
     * @param userId       userId (có hoặc không có tiền tố "USER#")
     * @param limit        số bản ghi tối đa cần lấy
     * @param startSortKey null cho trang đầu; SK của item cuối trang trước cho trang tiếp theo
     */
    List<CheckRecord> listRecentChecks(String userId, int limit, String startSortKey);
}
