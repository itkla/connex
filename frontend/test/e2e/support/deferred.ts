/** A promise released explicitly by the scenario that owns the request gate. */
export function deferred<T>() {
    const state: {
        resolve?: (value: T) => void;
        reject?: (reason?: unknown) => void;
    } = {};
    const promise = new Promise<T>((resolve, reject) => {
        state.resolve = resolve;
        state.reject = reject;
    });
    return {
        promise,
        resolve(value: T) {
            const callback = state.resolve;
            if (!callback) throw new Error("Deferred resolver is unavailable");
            callback(value);
        },
        reject(reason: unknown) {
            const callback = state.reject;
            if (!callback) throw new Error("Deferred rejecter is unavailable");
            callback(reason);
        },
    };
}
