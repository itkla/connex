export default function ContactPage({ params }: { params: { id: string } }) {
    return (
        <div>
            <h1>Contact</h1>
            <p>ID: {params.id}</p>
        </div>
    )
}