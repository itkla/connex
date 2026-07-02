/**
 * Inline SVG filter for the liquid-glass refraction layer. Mounted ONCE (near the map control) so the
 * {@code #lg-refract} id resolves for {@code backdrop-filter: url(#lg-refract)}. The filter turbulently
 * displaces the backdrop (the relationship map behind the control) with a per-channel chromatic split,
 * so on browsers that support SVG filters in backdrop-filter (Chromium) the map genuinely bends and
 * fringes at the glass. Elsewhere the reference is ignored and the CSS frosted base stands in.
 */
export default function LiquidGlassDefs() {
    return (
        <svg aria-hidden focusable="false" width="0" height="0" className="absolute" style={{ position: 'absolute' }}>
            <defs>
                <filter id="lg-refract" x="-25%" y="-25%" width="150%" height="150%" colorInterpolationFilters="sRGB">
                    <feTurbulence type="fractalNoise" baseFrequency="0.012 0.016" numOctaves={2} seed={7} stitchTiles="stitch" result="noise">
                        <animate attributeName="seed" values="7;57;7" dur="16s" repeatCount="indefinite" />
                    </feTurbulence>
                    <feGaussianBlur in="noise" stdDeviation="1.2" result="warp" />
                    <feColorMatrix in="SourceGraphic" type="matrix" result="r" values="1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0" />
                    <feDisplacementMap in="r" in2="warp" scale="30" xChannelSelector="R" yChannelSelector="G" result="rd" />
                    <feColorMatrix in="SourceGraphic" type="matrix" result="g" values="0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0" />
                    <feDisplacementMap in="g" in2="warp" scale="26" xChannelSelector="R" yChannelSelector="G" result="gd" />
                    <feColorMatrix in="SourceGraphic" type="matrix" result="b" values="0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0" />
                    <feDisplacementMap in="b" in2="warp" scale="22" xChannelSelector="R" yChannelSelector="G" result="bd" />
                    <feBlend in="rd" in2="gd" mode="screen" result="rg" />
                    <feBlend in="rg" in2="bd" mode="screen" />
                </filter>
            </defs>
        </svg>
    );
}
