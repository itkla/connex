import { describe, expect, it } from 'vitest';

import {
    appendCommentImage,
    commentImageAlt,
    commentImageMarkdown,
    commentPlainText,
} from '@/app/components/records/comments/commentText';

describe('commentImageAlt', () => {
    it('drops the extension and collapses separators', () => {
        expect(commentImageAlt('quarterly_report-chart.png')).toBe('quarterly report chart');
    });

    it('falls back to the raw name when nothing remains', () => {
        expect(commentImageAlt('.png')).toBe('.png');
    });
});

describe('commentImageMarkdown', () => {
    it('builds a well-formed image embed', () => {
        expect(commentImageMarkdown('chart.png', '/api/attachments/content/abc')).toBe(
            '![chart](/api/attachments/content/abc)',
        );
    });

    it('strips markdown-breaking characters from the alt text', () => {
        expect(
            commentImageMarkdown('a](x)![b `c`\\.png', '/api/attachments/content/abc'),
        ).toBe('![a x b c](/api/attachments/content/abc)');
    });

    it('never emits an empty alt segment', () => {
        expect(commentImageMarkdown('[]().png', '/api/attachments/content/abc')).toBe(
            '![image](/api/attachments/content/abc)',
        );
    });
});

describe('appendCommentImage', () => {
    it('starts an empty body with the embed alone', () => {
        expect(appendCommentImage('', '![a](/x)')).toBe('![a](/x)');
    });

    it('appends after existing text on its own paragraph', () => {
        expect(appendCommentImage('hello\n', '![a](/x)')).toBe('hello\n\n![a](/x)');
    });
});

describe('commentPlainText image handling', () => {
    it('reduces an image embed to its alt text without a bang', () => {
        expect(commentPlainText('see ![the chart](/api/attachments/content/abc)')).toBe(
            'see the chart',
        );
    });
});
