import type { Metadata, Viewport } from "next";
import { Inter, Instrument_Serif, Noto_Sans_JP, Noto_Serif_JP } from "next/font/google";
import { headers } from "next/headers";
import { connection } from "next/server";
import { NextIntlClientProvider } from "next-intl";
import { getLocale, getTranslations } from "next-intl/server";
import { requestOrigin } from "@/app/lib/requestHost";
import { openGraphLocales } from "@/app/lib/siteMetadata";
import { resolveLocale } from "@/i18n/config";
import { TooltipProvider } from "@/components/ui/tooltip";
import { ThemeProvider } from "@/app/components/ThemeProvider";
import "./globals.css";

const inter = Inter({ variable: "--font-inter", subsets: ["latin"] });

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
 * Site-wide metadata, matching the product application's root layout. `metadataBase` is read from
 * the request so canonical and Open Graph URLs name the host that served the page.
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
        </ThemeProvider>
      </body>
    </html>
  );
}
