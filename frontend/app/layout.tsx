import type { Metadata, Viewport } from "next";
import { Inter, Instrument_Serif, Noto_Sans_JP, Noto_Serif_JP } from "next/font/google";
import { headers } from "next/headers";
import { connection } from "next/server";
import { NextIntlClientProvider } from "next-intl";
import { getLocale, getTranslations } from "next-intl/server";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { ThemeProvider } from "@/app/components/ThemeProvider";
import { requestOrigin } from "@/app/lib/requestHost";
import { openGraphLocales } from "@/app/lib/siteMetadata";
import { resolveLocale } from "@/i18n/config";
import "./globals.css";

const inter = Inter({
  variable: "--font-inter",
  subsets: ["latin"],
});

// const geistMono = Geist_Mono({
//   variable: "--font-geist-mono",
//   subsets: ["latin"],
// });

const instrumentSerif = Instrument_Serif({
  weight: "400",
  variable: "--font-instrument-serif",
  subsets: ["latin"],
});

const notoSansJP = Noto_Sans_JP({
  weight: ["400", "700"],
  variable: "--font-noto-sans-jp",
  subsets: ["latin"],
});

// Japanese display face. `.font-display` used to fall back to the body sans, so a Japanese heading
// and Japanese body copy were the same family at the same weight — English got a serif/sans
// contrast that Japanese did not. This restores the pairing on both scripts.
const notoSerifJP = Noto_Serif_JP({
  weight: ["600"],
  variable: "--font-noto-serif-jp",
  subsets: ["latin"],
});

/** The brand colour behind the address bar and the installed app, from `--color-brand`. */
export const viewport: Viewport = {
  themeColor: "#73d200",
};

/**
 * Site-wide metadata. One process answers for several hostnames, so `metadataBase` — which every
 * relative canonical, Open Graph image, and alternate link resolves against — is read from the
 * request rather than from a baked environment value; a single origin would publish another host's
 * address on every page this host served.
 *
 * The inherited Open Graph block carries identity only. A title, description, or URL here would
 * override the page's own on every route that states one, so each page would share as the site
 * front door rather than as itself.
 * @returns the default title, description, and Open Graph identity every route inherits
 */
export async function generateMetadata(): Promise<Metadata> {
  const [locale, requestHeaders, t, common] = await Promise.all([
    getLocale(),
    headers(),
    getTranslations("AppMetadata"),
    getTranslations("CommonHome"),
  ]);
  const origin = requestOrigin(requestHeaders);

  return {
    metadataBase: origin ? new URL(origin) : null,
    title: { default: t("title"), template: "%s — Connex" },
    description: t("description"),
    openGraph: {
      type: "website",
      siteName: common("brand"),
      ...openGraphLocales(resolveLocale(locale)),
    },
  };
}

export default async function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  await connection();
  const [locale, requestHeaders] = await Promise.all([getLocale(), headers()]);
  const nonce = requestHeaders.get("x-nonce") ?? undefined;

  return (
    <html
      lang={locale}
      suppressHydrationWarning
      className={`${inter.variable} ${instrumentSerif.variable} ${notoSansJP.variable} ${notoSerifJP.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <ThemeProvider nonce={nonce}>
          <NextIntlClientProvider>
            <TooltipProvider>{children}</TooltipProvider>
          </NextIntlClientProvider>
          <Toaster position="top-center" />
        </ThemeProvider>
      </body>
    </html>
  );
}
