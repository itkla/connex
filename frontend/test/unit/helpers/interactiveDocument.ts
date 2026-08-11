import { vi } from "vitest";

type InteractiveListener = {
    callback: (event: unknown) => void;
    capture: boolean;
};

type InteractiveText = {
    nodeType: 3;
    nodeName: "#text";
    nodeValue: string;
    parentNode: InteractiveElement | null;
    ownerDocument: InteractiveDocument;
};

export type InteractiveElement = {
    nodeType: 1;
    tagName: string;
    nodeName: string;
    namespaceURI: string;
    ownerDocument: InteractiveDocument;
    parentNode: InteractiveElement | null;
    childNodes: Array<InteractiveElement | InteractiveText>;
    attributes: Map<string, string>;
    listeners: Map<string, InteractiveListener[]>;
    style: Record<string, unknown>;
    textContent: string;
    disabled?: boolean;
    id?: string;
    type?: string;
    value?: string;
    addEventListener: (type: string, callback: (event: unknown) => void, options?: unknown) => void;
    removeEventListener: (type: string, callback: (event: unknown) => void) => void;
    appendChild: (child: InteractiveNode) => InteractiveNode;
    insertBefore: (child: InteractiveNode, before: InteractiveNode | null) => InteractiveNode;
    removeChild: (child: InteractiveNode) => InteractiveNode;
    setAttribute: (name: string, value: string) => void;
    removeAttribute: (name: string) => void;
    getAttribute: (name: string) => string | null;
    focus: () => void;
};

type InteractiveNode = InteractiveElement | InteractiveText;

type InteractiveDocument = {
    nodeType: 9;
    cookie: string;
    activeElement: InteractiveElement | null;
    defaultView?: object;
    documentElement?: InteractiveElement;
    body?: InteractiveElement;
    addEventListener: (type: string, callback: (event: unknown) => void, options?: unknown) => void;
    removeEventListener: (type: string, callback: (event: unknown) => void) => void;
    createElement: (tagName: string) => InteractiveElement;
    createElementNS: (namespace: string, tagName: string) => InteractiveElement;
    createTextNode: (value: string) => InteractiveText;
    getElementById: (id: string) => InteractiveElement | null;
};

function eventUsesCapture(options: unknown): boolean {
    if (typeof options === "boolean") return options;
    return typeof options === "object"
        && options !== null
        && "capture" in options
        && options.capture === true;
}

function nodeText(node: InteractiveNode): string {
    return node.nodeType === 3
        ? node.nodeValue
        : node.childNodes.map(nodeText).join("");
}

export function installInteractiveDocument(cookie = "") {
    class HtmlIFrameElement {}

    const elements: InteractiveElement[] = [];
    const documentListeners = new Map<string, InteractiveListener[]>();

    function addListener(
        listeners: Map<string, InteractiveListener[]>,
        type: string,
        callback: (event: unknown) => void,
        options?: unknown,
    ) {
        const current = listeners.get(type) ?? [];
        current.push({ callback, capture: eventUsesCapture(options) });
        listeners.set(type, current);
    }

    function removeListener(
        listeners: Map<string, InteractiveListener[]>,
        type: string,
        callback: (event: unknown) => void,
    ) {
        listeners.set(type, (listeners.get(type) ?? []).filter(
            (listener) => listener.callback !== callback,
        ));
    }

    function createInteractiveElement(
        tagName: string,
        namespaceURI = "http://www.w3.org/1999/xhtml",
    ) {
        const childNodes: InteractiveNode[] = [];
        const attributes = new Map<string, string>();
        const listeners = new Map<string, InteractiveListener[]>();
        const element: InteractiveElement = {
            nodeType: 1,
            tagName: tagName.toUpperCase(),
            nodeName: tagName.toUpperCase(),
            namespaceURI,
            ownerDocument: documentTarget,
            parentNode: null,
            childNodes,
            attributes,
            listeners,
            style: {},
            textContent: "",
            addEventListener: (type, callback, options) => {
                addListener(listeners, type, callback, options);
            },
            removeEventListener: (type, callback) => {
                removeListener(listeners, type, callback);
            },
            appendChild: (child) => {
                child.parentNode = element;
                childNodes.push(child);
                return child;
            },
            insertBefore: (child, before) => {
                child.parentNode = element;
                const index = before === null ? -1 : childNodes.indexOf(before);
                if (index < 0) childNodes.push(child);
                else childNodes.splice(index, 0, child);
                return child;
            },
            removeChild: (child) => {
                const index = childNodes.indexOf(child);
                if (index >= 0) childNodes.splice(index, 1);
                child.parentNode = null;
                return child;
            },
            setAttribute: (name, value) => {
                attributes.set(name, String(value));
                if (name === "id") element.id = String(value);
                if (name === "type") element.type = String(value);
                if (name === "disabled") element.disabled = true;
            },
            removeAttribute: (name) => {
                attributes.delete(name);
                if (name === "disabled") element.disabled = false;
            },
            getAttribute: (name) => attributes.get(name) ?? null,
            focus: () => {
                documentTarget.activeElement = element;
            },
        };
        Object.defineProperties(element, {
            firstChild: { get: () => childNodes[0] ?? null },
            lastChild: { get: () => childNodes.at(-1) ?? null },
            textContent: {
                get: () => childNodes.map(nodeText).join(""),
                set: (value: string) => {
                    childNodes.forEach((child) => {
                        child.parentNode = null;
                    });
                    childNodes.length = 0;
                    if (value.length > 0) {
                        const child = documentTarget.createTextNode(value);
                        child.parentNode = element;
                        childNodes.push(child);
                    }
                },
            },
        });
        elements.push(element);
        return element;
    }

    const documentTarget: InteractiveDocument = {
        nodeType: 9,
        cookie,
        activeElement: null,
        addEventListener: (type, callback, options) => {
            addListener(documentListeners, type, callback, options);
        },
        removeEventListener: (type, callback) => {
            removeListener(documentListeners, type, callback);
        },
        createElement: (tagName) => createInteractiveElement(tagName),
        createElementNS: (namespace, tagName) => createInteractiveElement(tagName, namespace),
        createTextNode: (value) => ({
            nodeType: 3,
            nodeName: "#text",
            nodeValue: value,
            parentNode: null,
            ownerDocument: documentTarget,
        }),
        getElementById: (id) => elements.find((element) => element.id === id) ?? null,
    };
    const documentElement = createInteractiveElement("html");
    const body = createInteractiveElement("body");
    documentElement.appendChild(body);
    const windowTarget = {
        document: documentTarget,
        event: undefined,
        HTMLIFrameElement: HtmlIFrameElement,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        setTimeout: vi.fn(() => 1),
        clearTimeout: vi.fn(),
    };
    Object.assign(documentTarget, {
        defaultView: windowTarget,
        documentElement,
        body,
    });
    vi.stubGlobal("window", windowTarget);
    vi.stubGlobal("document", documentTarget);
    vi.stubGlobal("requestAnimationFrame", vi.fn((callback: FrameRequestCallback) => {
        callback(0);
        return 1;
    }));
    vi.stubGlobal("cancelAnimationFrame", vi.fn());
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    const container = document.createElement("div");
    const containerNode = elements.at(-1);
    if (!containerNode) throw new Error("Interactive root was not created");
    body.appendChild(containerNode);

    return {
        container,
        elements,
        dispatch: (type: string, target: InteractiveElement) => {
            let defaultPrevented = false;
            let propagationStopped = false;
            const event = {
                type,
                target,
                currentTarget: containerNode,
                bubbles: true,
                cancelable: true,
                defaultPrevented,
                returnValue: true,
                cancelBubble: false,
                timeStamp: Date.now(),
                preventDefault: () => {
                    defaultPrevented = true;
                    event.defaultPrevented = true;
                    event.returnValue = false;
                },
                stopPropagation: () => {
                    propagationStopped = true;
                    event.cancelBubble = true;
                },
                stopImmediatePropagation: () => {
                    propagationStopped = true;
                    event.cancelBubble = true;
                },
                composedPath: () => {
                    const path: InteractiveElement[] = [];
                    let current: InteractiveElement | null = target;
                    while (current !== null) {
                        path.push(current);
                        current = current.parentNode;
                    }
                    return path;
                },
            };
            const listeners = containerNode.listeners.get(type) ?? [];
            for (const listener of listeners.filter((candidate) => candidate.capture)) {
                listener.callback(event);
                if (propagationStopped) return !defaultPrevented;
            }
            for (const listener of listeners.filter((candidate) => !candidate.capture)) {
                listener.callback(event);
                if (propagationStopped) break;
            }
            return !defaultPrevented;
        },
    };
}
