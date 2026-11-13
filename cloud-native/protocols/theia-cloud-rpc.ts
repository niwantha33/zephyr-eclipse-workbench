// SPDX-License-Identifier: Apache-2.0
// Theia frontend/backend JSON-RPC. Implement as a connection-scoped
// RpcConnectionHandler only when a Theia application is assembled.
export const CLOUD_BACKEND_PATH = '/services/zew/cloud/v1';
export type OperationState = 'unavailable' | 'review-required' | 'running' | 'failed' | 'completed';
export interface RpcError { code: string; message: string; retryable: boolean; }
export interface ExtensionEntry {
  id: string;
  version: string;
  category: 'editor' | 'zephyr-module';
  publisher: string;
  license: string;
  verifiedDigest: string | null;
}
export interface CloudBackendRpc {
  capabilityStatus(): Promise<Record<string, OperationState>>;
  listRegistryItems(query: string, type: 'editor' | 'zephyr-module'): Promise<ExtensionEntry[]>;
  planInstallation(itemId: string): Promise<{ reviewId: string; actions: string[] }>;
  // applyInstallation must be a distinct, user-approved operation with a review token.
  applyInstallation(reviewId: string, approved: boolean): Promise<OperationState>;
  listRemoteBoards(): Promise<Array<{ boardId: string; zephyrBoard: string; available: boolean }>>;
  acquireRemoteBoard(boardId: string, leaseSeconds: number): Promise<{ leaseId: string; expiresAt: number }>;
  releaseRemoteBoard(boardId: string, leaseId: string): Promise<void>;
  queryApprovedDocuments(vendor: string, part: string, question: string):
    Promise<Array<{ title: string; sourceUri: string; revision: string; excerpt: string }>>;
  planBoardVerification(board: string): Promise<{ reviewId: string; commands: string[][] }>;
  runBoardVerification(reviewId: string, approved: boolean): Promise<OperationState>;
  telemetryPreview(): Promise<{ rows: Array<Record<string, string | number>> }>;
  telemetryConsent(enabled: boolean): Promise<boolean>;
  telemetryFlush(approved: boolean): Promise<boolean>;
}
export interface CloudFrontendRpc {
  onOperationChanged(operationId: string, state: OperationState, message: string): void;
}
