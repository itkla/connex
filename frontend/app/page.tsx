// import Image from "next/image";
import ProfileCard from "./components/ProfileCard";

export default function Home() {
  return (
    <div className="min-h-screen bg-white">
      <header className="mx-auto flex max-w-7xl items-center justify-between px-8 py-6">
        <div className="flex items-center gap-12">
          <a href="/" className="text-2xl font-bold tracking-tight text-black">
            CONNEX
          </a>
          <nav className="hidden items-center gap-8 md:flex">
            <a href="#pricing" className="text-base text-black hover:text-neutral-600">
              Pricing
            </a>
            <a href="#features" className="text-base text-black hover:text-neutral-600">
              Features
            </a>
          </nav>
        </div>
        <a
          href="#get-started"
          className="rounded-md bg-[#73D200] px-6 py-3 text-base font-medium text-white shadow-sm transition hover:bg-[#5da600]"
        >
          Get started
        </a>
      </header>

      <main className="mx-auto grid max-w-7xl grid-cols-1 items-center gap-12 px-8 pt-12 pb-24 lg:grid-cols-2 lg:gap-16 lg:pt-24">
        <section className="max-w-4xl">
          <h1 className="font-serif text-6xl leading-[1.05] tracking-tight text-black sm:text-7xl lg:text-[88px]">
            Build <em className="italic">Meaningful</em> Connections with your
            Clients
          </h1>
          <div className="mt-12">
            <a
              href="#get-started"
              className="inline-flex items-center justify-center rounded-full bg-[#73D200] px-10 py-4 text-lg font-medium text-white shadow-sm transition hover:bg-[#5da600]"
            >
              Get started
            </a>
          </div>
        </section>

        <section className="lg:justify-self-end">
          <ProfileCard />
        </section>
      </main>
    </div>
  );
}
