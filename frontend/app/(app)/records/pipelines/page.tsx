import { headers } from "next/headers";
import { getPipelinesFromCookie, getCurrentUserFromCookie } from "@/app/lib/api";
import { redirect } from "next/navigation";

export default async function PipelinesPage() {

    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    // query api for pipeline list
    const pipelines = await getPipelinesFromCookie(cookie); 

    return (
        <div>
            <h1>Pipelines</h1>
            <table>
                <thead>
                    <tr>
                        <th>Name</th>
                        <th>Created At</th>
                        <th>Updated At</th>
                    </tr>
                </thead>
                <tbody>
                    {pipelines.map((pipeline) => (
                        <tr key={pipeline.id}>
                            <td>{pipeline.name}</td>
                            <td>{pipeline.createdAt}</td>
                            <td>{pipeline.updatedAt}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
       
        </div>
    );
}
