import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getPipelines, getCurrentUserFromCookie } from "@/app/lib/api";
import { type Pipeline } from "@/app/lib/types";
import PipelinesBrowser from "@/app/components/records/pipelines/PipelinesBrowser";

export default async function PipelinesPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const pipelines: Pipeline[] = await getPipelines({
        headers: { cookie: cookie ?? "" },
        cache: "no-store",
    });

    return <PipelinesBrowser pipelines={pipelines} />;
}
