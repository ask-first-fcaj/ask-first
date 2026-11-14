package com.askfirst.common.store;

import com.askfirst.common.ai.AiContract.Flags;

/** CONFIG#global / FLAGS: công tắc vận hành. Thiếu item hoặc thiếu field thì dùng giá trị mặc định. */
public interface FlagsStore {

    /** Mặc định khi chưa cấu hình: voice tắt (chưa hỗ trợ), còn lại bật. */
    Flags DEFAULTS = new Flags(true, true, false, true);

    Flags load();
}
