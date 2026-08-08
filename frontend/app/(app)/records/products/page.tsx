import { headers } from "next/headers";
import { redirect } from "next/navigation";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getProducts, getCurrentUserResultFromCookie } from "@/app/lib/api";
import { type Product } from "@/app/lib/types";
import ProductsBrowser from "@/app/components/records/products/ProductsBrowser";

export default async function ProductsPage() {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);

    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;

    if (!user) {
        redirect('/auth/login');
    }

    const products: Product[] = await getProducts({}, {
        headers: { cookie: cookie ?? "" },
        cache: "no-store",
    });

    return <ProductsBrowser products={products} />;
}
