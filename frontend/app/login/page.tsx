export default function Login() {
    return (
        <div className="min-h-screen bg-white">
            <header className="mx-auto flex max-w-7xl items-center justify-between px-8 py-6">
                <div className="flex items-center gap-12">
                    <a href="/" className="text-2xl font-bold tracking-tight text-black">
                        Sign in to
                    </a>
                </div>
                <div className="flex items-center gap-12">
                    <a href="/" className="text-2xl font-bold tracking-tight text-black">
                        CONNEX
                    </a>
                </div>
            </header>
            <main className="mx-auto max-w-7xl px-8 py-12">
                <div className="max-w-md">
                    <h1 className="text-3xl font-bold text-gray-900">Login</h1>
                    <p className="mt-4 text-gray-600">
                        Welcome back! Please enter your credentials to access your account.
                    </p>
                </div>
            </main>
        </div>
    )
}