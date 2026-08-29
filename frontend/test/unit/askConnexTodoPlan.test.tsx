import { NextIntlClientProvider } from "next-intl";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { TodoPlan } from "@/app/components/ask-connex/AskConnexDrawer";
import { ASK_CONNEX_TODO_LIMIT, askConnexMessageTodos } from "@/app/lib/askConnex";
import type { AiChatTodo } from "@/app/lib/types";

const LABEL = "Plan";

const PLAN: AiChatTodo[] = [
    { label: "Check the contact", status: "done" },
    { label: "Read their open deals", status: "active" },
    { label: "Summarize what needs attention", status: "pending" },
];

const STATUS_LABELS = { pending: "To do", active: "In progress", done: "Done" } as const;

function render(todos: readonly AiChatTodo[], live = false): string {
    return renderToStaticMarkup(
        <NextIntlClientProvider locale="en" messages={{}}>
            <TodoPlan todos={todos} label={LABEL} statusLabels={STATUS_LABELS} live={live} />
        </NextIntlClientProvider>,
    );
}

describe("the assistant's published plan", () => {
    it("shows every step in the order the assistant planned it", () => {
        const html = render(PLAN);

        expect(html).toContain(`aria-label="${LABEL}"`);
        const positions = PLAN.map((todo) => html.indexOf(todo.label));
        expect(positions.every((position) => position >= 0)).toBe(true);
        expect(positions).toEqual([...positions].sort((a, b) => a - b));
    });

    it("distinguishes finished work from the step running now", () => {
        const html = render(PLAN, true);

        expect(html).toContain("line-through");
        expect(html).toContain("animate-spin");
        expect(html).toContain("motion-reduce:animate-none");
    });

    it("stops animating once the turn it belonged to has settled", () => {
        expect(render(PLAN)).not.toContain("animate-spin");
        expect(render(PLAN, true)).toContain("animate-spin");
    });

    it("names each step's state for readers who never see its glyph", () => {
        const html = render(PLAN);

        for (const status of ["Done", "In progress", "To do"]) {
            expect(html).toContain(status);
        }
    });

    it("keys repeated step labels apart without leaning on their position", () => {
        const repeated: AiChatTodo[] = [
            { label: "Review", status: "done" },
            { label: "Review", status: "pending" },
        ];

        expect(() => render(repeated)).not.toThrow();
        expect(render(repeated).match(/Review/g)).toHaveLength(2);
    });

    it("renders a step as its own text, never as a link", () => {
        const html = render([
            { label: "Read [Acme](company:45) and [Renewal](record:r1)", status: "pending" },
        ]);

        expect(html).not.toContain("<a");
        expect(html).not.toContain("/records/companies/45");
        expect(html).toContain("company:45");
    });

    it("renders nothing at all when no plan was published", () => {
        expect(render([])).toBe("");
    });
});

describe("a settled answer's stored plan", () => {
    it("is read back as published", () => {
        expect(askConnexMessageTodos({ todos: PLAN })).toEqual(PLAN);
    });

    it("treats a missing, null, or empty plan as the same shared empty plan", () => {
        const missing = askConnexMessageTodos({});
        expect(missing).toHaveLength(0);
        expect(askConnexMessageTodos({ todos: null })).toBe(missing);
        expect(askConnexMessageTodos({ todos: [] })).toBe(missing);
    });

    it("bounds a stored plan written before the server bounded them", () => {
        const overlong: AiChatTodo[] = Array.from({ length: 40 }, (_, index) => ({
            label: `Step ${index}`,
            status: "pending" as const,
        }));

        expect(askConnexMessageTodos({ todos: overlong })).toHaveLength(ASK_CONNEX_TODO_LIMIT);
    });
});
