// SPDX-License-Identifier: Apache-2.0
import type { BoardDescriptor, BoardLease } from './contracts.js';

export interface LabAuthorizer {
  canUse(userId: string, boardId: string): Promise<boolean>;
}
export class LabLeaseRegistry {
  private byBoard = new Map<string, BoardLease>();
  constructor(private readonly authorizer: LabAuthorizer,
              private readonly now: () => number,
              private readonly newId: () => string) {}
  async acquire(userId: string, board: BoardDescriptor, ttlMs: number): Promise<BoardLease> {
    if (!userId || !board.available || !Number.isSafeInteger(ttlMs) ||
        ttlMs < 1_000 || ttlMs > 3_600_000) {
      throw new Error('Invalid board reservation');
    }
    if (!await this.authorizer.canUse(userId, board.boardId)) throw new Error('Access denied');
    const existing = this.byBoard.get(board.boardId);
    if (existing && existing.expiresAtMs > this.now()) throw new Error('Board already leased');
    const lease = { userId, boardId: board.boardId, leaseId: this.newId(),
                    expiresAtMs: this.now() + ttlMs };
    this.byBoard.set(board.boardId, lease);
    return { ...lease };
  }
  verify(userId: string, boardId: string, leaseId: string): void {
    const lease = this.byBoard.get(boardId);
    if (!lease || lease.leaseId !== leaseId || lease.userId !== userId ||
        lease.expiresAtMs <= this.now()) throw new Error('Missing or expired board lease');
  }
  release(userId: string, boardId: string, leaseId: string): void {
    this.verify(userId, boardId, leaseId);
    this.byBoard.delete(boardId);
  }
}
