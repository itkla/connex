import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getProducts, getCurrentUserFromCookie } from "@/app/lib/api";
import { type Product } from "@/app/lib/types";
import ProductsBrowser from "@/app/components/records/products/ProductsBrowser";

export default async function ProductsPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const products: Product[] = await getProducts({}, {
        headers: { cookie: cookie ?? "" },
        cache: "no-store",
    });

    return <ProductsBrowser products={products} />;
}
