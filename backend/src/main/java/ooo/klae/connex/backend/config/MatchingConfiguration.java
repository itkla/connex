package ooo.klae.connex.backend.config;

import java.util.Objects;

import org.apache.hc.client5.http.psl.PublicSuffixMatcher;
import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.i18n.phonenumbers.PhoneNumberUtil;

/**
 * Immutable normalization dependencies shared by canonical identity matching.
 */
@Configuration
public class MatchingConfiguration {

    /**
     * Supplies the metadata-backed phone parser.
     * @return the thread-safe phone utility
     */
    @Bean
    public PhoneNumberUtil phoneNumberUtil() {
        return PhoneNumberUtil.getInstance();
    }

    /**
     * Loads the bundled public-suffix rules and verifies required ICANN and PRIVATE behavior.
     * @return the validated suffix matcher
     */
    @Bean
    public PublicSuffixMatcher publicSuffixMatcher() {
        PublicSuffixMatcher matcher = PublicSuffixMatcherLoader.getDefault();
        requireDomainRoot(matcher, "x.example.co.jp", "example.co.jp");
        requireDomainRoot(matcher, "sub.example.github.io", "example.github.io");
        return matcher;
    }

    private void requireDomainRoot(PublicSuffixMatcher matcher, String host, String expected) {
        if (!Objects.equals(expected, matcher.getDomainRoot(host))) {
            throw new IllegalStateException("Bundled public-suffix rules failed a required matching invariant");
        }
    }
}
