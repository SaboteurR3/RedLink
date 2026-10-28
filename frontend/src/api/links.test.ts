import { describe, expect, it } from 'vitest';
import { parseLinkHeader } from './links';

describe('parseLinkHeader', () => {
  it('reads each relation of a paged list', () => {
    const header =
      '</api/v1/audit?size=1&page=0>; rel="first", </api/v1/audit?size=1&page=0>; rel="prev", ' +
      '</api/v1/audit?size=1&page=2>; rel="next", </api/v1/audit?size=1&page=2>; rel="last"';
    expect(parseLinkHeader(header)).toEqual({
      first: '/api/v1/audit?size=1&page=0',
      prev: '/api/v1/audit?size=1&page=0',
      next: '/api/v1/audit?size=1&page=2',
      last: '/api/v1/audit?size=1&page=2',
    });
  });

  it('accepts unquoted and multiple relations, and no header at all', () => {
    expect(parseLinkHeader('</a>; rel=next, </b>; title="x"; rel="last up"')).toEqual({ next: '/a', last: '/b', up: '/b' });
    expect(parseLinkHeader(null)).toEqual({});
  });
});
