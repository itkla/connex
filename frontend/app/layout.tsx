import type { Metadata } from "next";
import { Inter, Instrument_Serif, Noto_Sans_JP, Noto_Serif_JP } from "next/font/google";
import { headers } from "next/headers";
import { connection } from "next/server";
import { NextIntlClientProvider } from "next-intl";
import { getLocale } from "next-intl/server";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { ThemeProvider } from "@/app/components/ThemeProvider";
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

export const metadata: Metadata = {
  title: "Connex",
  description: "Build Meaningful Connections with your Clients",
};

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
