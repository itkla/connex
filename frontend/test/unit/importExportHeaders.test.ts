import { describe, expect, it } from 'vitest';

import { IGNORE_COLUMN, suggestColumnTargets, suggestField } from '@/app/lib/import';
import type { ImportEntity } from '@/app/lib/types';

const EXPORT_HEADER_GROUPS = [
    {
        entity: 'persons', field: 'name',
        headers: ['Name', 'Full Name', 'Contact', 'Contact Name', 'Contact Full Name', 'Display Name'],
    },
    {
        entity: 'persons', field: 'givenName',
        headers: ['First Name', 'FirstName', 'Given Name'],
    },
    {
        entity: 'persons', field: 'familyName',
        headers: ['Last Name', 'LastName', 'Surname', 'Family Name'],
    },
    {
        entity: 'persons', field: 'email',
        headers: [
            'Email', 'Email Address', 'EmailAddress', 'E-mail', 'E-mail Address', 'Primary Email', 'Work Email',
            'Business Email', 'Personal Email', 'Other Email',
        ],
    },
    {
        entity: 'persons', field: 'phone',
        headers: [
            'Phone', 'Phone Number', 'PhoneNumber', 'Mobile', 'Mobile Phone', 'MobilePhone', 'Mobile phone number',
            'Mobile Number', 'Home Phone', 'HomePhone', 'Other Phone', 'OtherPhone', 'Business Phone', 'Work Phone',
            'Office Phone', 'Primary Phone', 'Telephone', 'Telephone Number', 'Cell Phone', 'Cell Phone Number',
        ],
    },
    {
        entity: 'persons', field: 'title',
        headers: ['Title', 'Job Title', 'JobTitle', 'Business Title', 'Role', 'Job Role', 'Position', 'Job Position'],
    },
    {
        entity: 'persons', field: 'company',
        headers: ['Company', 'Company Name', 'Account', 'Account Name', 'Employer', 'Organization', 'Organisation'],
    },
    {
        entity: 'persons', field: 'tags',
        headers: ['Tags', 'Labels'],
    },
    {
        entity: 'companies', field: 'name',
        headers: ['Name', 'Full Name', 'Display Name', 'Account Name', 'AccountName', 'Company', 'Company Name'],
    },
    {
        entity: 'companies', field: 'phone',
        headers: [
            'Phone', 'Phone Number', 'Main Phone', 'Office Phone', 'Business Phone', 'Corporate Phone',
            'Company Phone', 'Company Phone Number', 'Telephone Number',
        ],
    },
    {
        entity: 'companies', field: 'website',
        headers: [
            'Website', 'Website URL', 'WebsiteURL', 'Web', 'Site', 'Web Site', 'Web Address', 'Company Website',
            'Company Website URL', 'Company Domain', 'Domain Name', 'Company Domain Name', 'Domain', 'URL',
            'Homepage',
        ],
    },
    {
        entity: 'companies', field: 'industry',
        headers: ['Industry', 'Industry Sector', 'Business Sector', 'Sector', 'Vertical', 'Business Vertical'],
    },
    {
        entity: 'companies', field: 'address',
        headers: [
            'Address', 'Street Address', 'StreetAddress', 'Billing Address', 'BillingAddress', 'Shipping Address',
            'ShippingAddress', 'Mailing Address', 'Company Address', 'Office Address', 'Business Address',
            'Location',
        ],
    },
    {
        entity: 'companies', field: 'tags',
        headers: ['Tags', 'Labels'],
    },
    {
        entity: 'persons', field: null,
        headers: [
            'Contact ID', 'Contact Owner', 'Account ID', 'Company ID', 'Mailing Address', 'Mailing Street',
            'Mailing City', 'Mailing Country', 'Email Opt Out', 'Email Bounced Reason', 'EmailBouncedDate',
            'Additional Email Addresses', 'Lifecycle Stage', 'Lead Source', 'Lead Status', 'Website URL',
        ],
    },
    {
        entity: 'companies', field: null,
        headers: [
            'Account Site', 'Website Status', 'Website Visits', 'Domain Registration Date', 'Phone Extension',
            'Billing Street', 'Billing City', 'Billing State', 'Billing Postal Code', 'Billing Country',
            'Shipping Street', 'Shipping City', 'Annual Revenue', 'Number of Employees', 'Company Type',
            'Parent Company', 'Parent Account', 'Lifecycle Stage', 'Disqualification Reason Label', 'Email Address',
            'Job Title',
        ],
    },
] satisfies { entity: ImportEntity; field: string | null; headers: string[] }[];

describe('common contact and company export headers', () => {
    it.each(EXPORT_HEADER_GROUPS.flatMap(({ entity, field, headers }) =>
        headers.map((header) => ({ entity, field, header })),
    ))('maps $header on $entity to $field', ({ entity, field, header }) => {
        expect(suggestField(header, entity)).toBe(field);
    });

    it.each([
        { entity: 'persons', headers: ['Phone', 'Mobile Phone'] },
        { entity: 'persons', headers: ['Email', 'Work Email'] },
        { entity: 'companies', headers: ['Website', 'Website URL'] },
        { entity: 'companies', headers: ['Address', 'Street Address'] },
    ] satisfies { entity: ImportEntity; headers: string[] }[])(
        'leaves competing $entity aliases $headers for the user to choose',
        ({ entity, headers }) => {
            expect(suggestColumnTargets(headers, entity)).toEqual(
                Object.fromEntries(headers.map((header) => [header, IGNORE_COLUMN])),
            );
        },
    );
});
