package com.waka.identity.application;

import com.waka.identity.otp.PersistentOtpService;
import com.waka.identity.session.PersistentSessionService;
import com.waka.identity.session.SessionGrant;
import java.util.Objects;
import java.util.UUID;

/** Explicit assembly only: no provider, secret, demo transport or automatic Spring bean. */
public final class LoginService {
    private final PersistentOtpService otp;
    private final PersistentSessionService sessions;

    public LoginService(PersistentOtpService otp, PersistentSessionService sessions) {
        this.otp = Objects.requireNonNull(otp);
        this.sessions = Objects.requireNonNull(sessions);
    }

    public PersistentOtpService.Challenge request(String client, UUID requestKey, String phoneNumber) {
        return otp.request(client, requestKey, CameroonMobilePhoneFormat.normalize(phoneNumber));
    }

    public SessionGrant verify(UUID challenge, String client, UUID verificationKey,
            String phoneNumber, char[] code) {
        String phone = CameroonMobilePhoneFormat.normalize(phoneNumber);
        // Never accept a proof supplied over HTTP. verify rechecks the code and its replay binding.
        // Separate short transactions permit resuming after the OTP commit or session commit.
        var proof = otp.verifyBound(challenge, client, verificationKey, phone, code);
        return sessions.openOrReplay(proof, client, phone, verificationKey);
    }

    public void revoke(UUID sessionId, char[] token, String client, UUID revocationKey) {
        // authenticate first would wrongly reject a retry whose first logout already committed.
        sessions.revoke(sessionId, token, client, revocationKey);
    }

    @Override public String toString() { return "LoginService[REDACTED]"; }
}
