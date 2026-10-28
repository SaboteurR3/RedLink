/** Relation name → target, from an RFC 8288 `Link` header such as `</a?page=1>; rel="next", …`. */
export function parseLinkHeader(header: string | null): Record<string, string> {
  const links: Record<string, string> = {};
  if (!header) return links;
  // Targets cannot contain '>', so split on the commas between `<…>; params` entries.
  for (const match of header.matchAll(/<([^>]*)>([^,]*)/g)) {
    const [, target, params] = match;
    const rel = /;\s*rel="?([^";]+)"?/i.exec(params ?? '')?.[1];
    if (target !== undefined && rel) {
      for (const name of rel.trim().split(/\s+/)) links[name.toLowerCase()] = target;
    }
  }
  return links;
}
