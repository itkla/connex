const API_BASE =
  process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export type User = {
  id: number;
  username: string;
  displayName: string;
  email: string;
};

export type LoginPayload = {
  username: string;
  password: string;
};

export type RegisterPayload = {
  username: string;
  password: string;
  displayName: string;
  email: string;
};

/**
* Helper function to make POST requests with JSON body and parse JSON response.
* Throws an error if the response is not ok, including the response text if available.
* 
* @param path - The API endpoint path (e.g., "/api/auth/login")
* @param body - The request payload to be sent as JSON
* @returns A promise that resolves to the parsed JSON response of type T
*/
async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(text || `Request failed (${res.status})`);
  }
  return res.json();
}

/**
 * Logs in a user with the provided credentials.
 * 
 * @param payload - An object containing the username and password for login
 * @returns A promise that resolves to the logged-in user's information
 * @throws An error if the login request fails, including the response text if available
 */
export function login(payload: LoginPayload) {
  return postJson<User>("/api/auth/login", payload);
}

/**
 * Registers a new user with the provided information.
 * 
 * @param payload - An object containing the user's registration details
 * @returns A promise that resolves to the newly registered user's information
 * @throws An error if the registration request fails, including the response text if available
 */
export function register(payload: RegisterPayload) {
  return postJson<User>("/api/auth/register", payload);
}
