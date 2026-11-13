// SPDX-License-Identifier: Apache-2.0
import type { ReferenceDocument, RetrievalHit } from './contracts.js';

function cosine(a: readonly number[], b: readonly number[]): number {
  if (!a.length || a.length !== b.length) throw new Error('Embedding dimension mismatch');
  let dot = 0, aa = 0, bb = 0;
  for (let i = 0; i < a.length; i++) {
    const x = a[i]!, y = b[i]!;
    if (!Number.isFinite(x) || !Number.isFinite(y)) throw new Error('Invalid vector');
    dot += x * y; aa += x * x; bb += y * y;
  }
  return aa && bb ? dot / Math.sqrt(aa * bb) : 0;
}
export class ApprovedDocumentRetriever {
  constructor(private readonly docs: readonly ReferenceDocument[]) {}
  search(queryVector: readonly number[], vendor: string, part: string, limit = 5): RetrievalHit[] {
    if (!vendor || !part || !Number.isSafeInteger(limit) || limit < 1 || limit > 20) {
      throw new Error('Exact vendor and part required');
    }
    return this.docs.filter(d => d.approved && d.vendor === vendor && d.part === part &&
        /^https:\/\//.test(d.sourceUri) && d.revision.trim())
      .map(document => ({ document, score: cosine(queryVector, document.vector) }))
      .filter(hit => hit.score > 0)
      .sort((a, b) => b.score - a.score || a.document.id.localeCompare(b.document.id))
      .slice(0, limit);
  }
}
