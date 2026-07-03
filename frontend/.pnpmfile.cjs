/**
 * pnpm install hook. next@16.2.9 hard-pins postcss 8.4.31, which carries a build-time XSS
 * advisory (GHSA-qx2v-qp2m-jg93). Bump that transitive pin to the patched line so `pnpm audit`
 * stays clean; postcss 8.5.x is API-compatible with the styled-jsx build pipeline that uses it.
 */
function readPackage(pkg) {
    if (pkg.dependencies && pkg.dependencies.postcss === '8.4.31') {
        pkg.dependencies.postcss = '^8.5.10';
    }
    return pkg;
}

module.exports = { hooks: { readPackage } };
