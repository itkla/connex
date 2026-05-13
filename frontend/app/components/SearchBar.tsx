import { MagnifyingGlassIcon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";

// TODO: add omnisearch functionality using the params from the backend controllers

export default function SearchBar() {
    return (
        <form className="relative flex-1">
            <input
                type="text"
                placeholder="Search for anything"
                className="w-full rounded-full bg-neutral-100 px-4 py-2.5 pr-10 text-base text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand"
            />
            <Button
                type="submit"
                className="absolute right-2 top-1/2 -translate-y-1/2 p-0 bg-transparent border-none flex items-center justify-center hover:bg-transparent"
                tabIndex={-1}
                aria-label="Search"
            >
                <MagnifyingGlassIcon className="size-5 text-neutral-500" />
            </Button>
        </form>
  
    );
}