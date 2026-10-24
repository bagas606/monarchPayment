package id.ppob2.app.security;

import id.ppob2.admin.session.AdminProperties;
import org.springframework.http.ResponseCookie;

/** The one place that knows how the session cookie is built, so setting and clearing it cannot drift apart. */
public final class SessionCookies {

    private SessionCookies() {
    }

    public static ResponseCookie issue(AdminProperties props, String token, long maxAgeSeconds) {
        return base(props, token).maxAge(maxAgeSeconds).build();
    }

    public static ResponseCookie clear(AdminProperties props) {
        return base(props, "").maxAge(0).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(AdminProperties props, String value) {
        return ResponseCookie.from(props.session().cookieName(), value)
                .httpOnly(true)
                .secure(props.session().cookieSecure())
                // Strict: the cookie is not sent on any cross-site request, including top-level navigation.
                .sameSite("Strict")
                .path("/");
    }
}
