import { headers } from "next/headers";
import { getDealsFromCookie, getCurrentUserFromCookie } from "@/app/lib/api";
import { redirect } from "next/navigation";

export default async function DealsPage() {

    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    // query api for deal list
    const deals = await getDealsFromCookie(cookie); 

    return (
        <div>
            <h1>Deals</h1>
            <table>
                <thead>
                    <tr>
                        <th>Name</th>
                        <th>Value</th>
                        <th>Stage</th>
                        <th>Expected Close Date</th>
                    </tr>
                </thead>
                <tbody>
                    {deals.map((deal) => (
                        <tr key={deal.id}>
                            <td>{deal.name}</td>
                            <td>
                                {deal.value} {deal.currency}
                            </td>
                            <td>{deal.stage}</td>
                            <td>{deal.expectedCloseDate}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
       
        </div>
    );
}
