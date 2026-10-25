package ooo.klae.connex.backend.codeqlfixture;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Intentional CodeQL canary for the CHK-089 gate proof (docs/STATIC_ANALYSIS.md). Test-source only,
 * unannotated, never referenced and never merged: CodeQL must report java/command-line-injection.
 */
public final class IntentionalCommandInjectionFixture {

    private IntentionalCommandInjectionFixture() {
    }

    /**
     * Executes the caller-supplied command verbatim so the taint path is unmistakable.
     *
     * @param request the untrusted request carrying the command parameter
     * @return the started process
     * @throws IOException if the process cannot be started
     */
    public static Process execute(HttpServletRequest request) throws IOException {
        return Runtime.getRuntime().exec(request.getParameter("command"));
    }
}
