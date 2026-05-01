// import Image from "next/image";
import ProfileCard from "./components/ProfileCard";
import { ArrowRightCircleIcon } from "@heroicons/react/16/solid";

export default function Home() {
  return (
    <div className="min-h-screen bg-white max-w-full">
      <header className="flex items-center justify-between px-8 py-6">
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
          className="rounded-xl bg-[#73D200] px-6 py-3 text-base font-medium text-white shadow-sm transition hover:bg-[#5da600]"
        >
          Get started
        </a>
      </header>

      <main className="mx-auto grid max-w-7xl grid-cols-1 items-center gap-12 px-8 pt-12 pb-24 lg:grid-cols-2 lg:gap-16 lg:pt-24">
        <section className="max-w-4xl">
          <h1 className="font-['Instrument_Serif'] text-6xl leading-[1.05] tracking-tight text-black sm:text-7xl lg:text-[88px]">
            Build <em className="italic">Meaningful</em> Connections with your
            Clients
          </h1>
          <div className="mt-12">
            <a
              href="#get-started"
              className="inline-flex items-center justify-center rounded-xl bg-[#73D200] px-10 py-4 text-lg font-medium text-white shadow-sm transition hover:bg-[#5da600]"
            >
              Get started <ArrowRightCircleIcon className="ml-2 h-5 w-5" />
            </a>
          </div>
        </section>

        <section className="relative z-10 -mb-16 translate-y-12 lg:-mb-32 lg:translate-y-16 lg:justify-self-end">
          <ProfileCard />
        </section>
      </main>
      <hr />
      {/* <div id="aboutSection" className="mx-auto max-w-7xl px-8 py-12">
        <h2 className="text-3xl font-bold tracking-tight text-black sm:text-4xl">
          About Us
        </h2>
        <p className="mt-4 text-lg text-gray-700">
          At CONNEX, we are passionate about helping businesses build meaningful
          connections with their clients. Our platform provides powerful tools
          and insights to enhance customer engagement and drive growth.
        </p>
      </div> */}
    </div>
  );
}
