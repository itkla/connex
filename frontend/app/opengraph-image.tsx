import { ImageResponse } from "next/og";

import common from "@/messages/en/common.json";

/** The wordmark is the brand name, identical in every catalog, so the English one is the source. */
const WORDMARK = common.CommonHome.brand;

export const alt = WORDMARK;
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

const BRAND_COLOR = "#73d200";
const BACKGROUND_COLOR = "#ffffff";
const FOREGROUND_COLOR = "#0a0a0a";

/**
 * The share card every public route inherits. It carries the shipped identity and nothing else —
 * the brand square and the wordmark — because a claim about the product would have to be true on
 * the prelaunch host, the launched host, the documentation, and the legal pages alike.
 * @returns a 1200x630 PNG
 */
export default function OpenGraphImage() {
    return new ImageResponse(
        (
            <div
                style={{
                    width: "100%",
                    height: "100%",
                    display: "flex",
                    flexDirection: "column",
                    alignItems: "center",
                    justifyContent: "center",
                    backgroundColor: BACKGROUND_COLOR,
                    padding: "120px",
                }}
            >
                <div style={{ display: "flex", alignItems: "center", gap: "40px" }}>
                    <div
                        style={{
                            width: "128px",
                            height: "128px",
                            borderRadius: "32px",
                            backgroundColor: BRAND_COLOR,
                        }}
                    />
                    <div
                        style={{
                            fontSize: "112px",
                            letterSpacing: "-0.03em",
                            color: FOREGROUND_COLOR,
                        }}
                    >
                        {WORDMARK}
                    </div>
                </div>
            </div>
        ),
        size,
    );
}
