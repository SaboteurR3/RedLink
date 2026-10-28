package ge.tsu.redlink.missioncontrol.auth;

import ge.tsu.redlink.missioncontrol.user.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * Exchanges a username and password for an access token, with brute-force throttling.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final LoginThrottle loginThrottle;

    /**
     * @throws LoginThrottledException  if too many attempts failed recently (checked before the
     *                                  password, so a locked key cannot keep guessing)
     * @throws AuthenticationException  if the credentials are wrong or the account is disabled
     */
    public TokenService.IssuedToken login(String username, String password, String clientIp) {
        String normalized = UserAccount.normalizeUsername(username);
        loginThrottle.checkAllowed(normalized, clientIp);
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(normalized, password));
        } catch (AuthenticationException e) {
            loginThrottle.recordFailure(normalized, clientIp);
            throw e;
        }
        loginThrottle.recordSuccess(normalized, clientIp);
        return tokenService.issue(authentication);
    }
}
