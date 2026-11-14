package com.askfirst.worker;

import com.askfirst.common.store.GuardianRecord;
import com.askfirst.common.store.GuardianRepo;
import com.askfirst.common.store.UserRecord;
import com.askfirst.common.store.UserRepo;
import java.util.List;
import java.util.Optional;

/** {@link GuardianDirectory} trên UserRepo và GuardianRepo có sẵn. */
public final class RepoGuardianDirectory implements GuardianDirectory {

    private final UserRepo users;
    private final GuardianRepo guardians;

    public RepoGuardianDirectory(UserRepo users, GuardianRepo guardians) {
        this.users = users;
        this.guardians = guardians;
    }

    @Override
    public Optional<Elder> elder(String userId) {
        return users.getUser(userId).map(u -> new Elder(
                u.getAddressForm() == null ? UserRecord.DEFAULT_ADDRESS_FORM : u.getAddressForm(),
                u.getPurposes() != null && Boolean.TRUE.equals(u.getPurposes().get("guardian"))));
    }

    @Override
    public List<Guardian> activeGuardians(String elderUid) {
        return guardians.listGuardians(elderUid).stream()
                .filter(g -> "active".equals(g.getStatus()))
                .map(RepoGuardianDirectory::toGuardian)
                .toList();
    }

    private static Guardian toGuardian(GuardianRecord g) {
        int level = g.getShareLevel() == null ? 1 : Math.max(1, Math.min(3, g.getShareLevel()));
        return new Guardian(g.getGuardianUid(), g.getGuardianChatId(), g.getName(), level);
    }
}
