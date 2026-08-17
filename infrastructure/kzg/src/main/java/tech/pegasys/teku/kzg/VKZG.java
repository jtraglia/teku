/*
 * Copyright Consensys Software Inc., 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.kzg;

import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import vkzg.Vkzg;

/**
 * GPU-based cell recovery and proof computation, via vkzg. This is not a full {@link KZG}
 * implementation -- vkzg has no commitment or blob-proof support -- so it's a narrow, standalone
 * wrapper used only for the two operations it accelerates: recovering missing cells and computing
 * cell KZG proofs, both batched across every blob in a reconstruction at once.
 */
public final class VKZG {
  private static final Logger LOG = LogManager.getLogger();

  private VKZG() {}

  /** Opens the GPU prover. Call once at startup, alongside the CKZG4844 trusted setup load. */
  public static void initialize() {
    Vkzg.init();
    Runtime.getRuntime().addShutdownHook(new Thread(Vkzg::deinit));
    LOG.info("vkzg initialized on {} ({} cores)", Vkzg.deviceName(), Vkzg.gpuCoreCount());
  }

  /**
   * Recovers every cell and computes its KZG proof for each blob's partial cells (at least half of
   * each blob's 128 cells must be present). Batches recovery and proof computation across every
   * blob in one GPU call each, then combines the results.
   */
  public static List<List<KZGCellAndProof>> recoverCellsAndProofs(
      final List<List<KZGCellWithColumnId>> perBlobKnownCells) {
    final int blobCount = perBlobKnownCells.size();
    if (blobCount == 0) {
      return List.of();
    }

    final byte[] cells = new byte[blobCount * Vkzg.CELL_PROOFS_PER_BLOB * Vkzg.BYTES_PER_CELL];
    final byte[] present = new byte[blobCount * Vkzg.CELL_PROOFS_PER_BLOB];
    for (int b = 0; b < blobCount; b++) {
      for (final KZGCellWithColumnId known : perBlobKnownCells.get(b)) {
        final int column = known.columnId().getColumnIndex();
        final int cellOffset = (b * Vkzg.CELL_PROOFS_PER_BLOB + column) * Vkzg.BYTES_PER_CELL;
        System.arraycopy(
            known.cell().bytes().toArrayUnsafe(), 0, cells, cellOffset, Vkzg.BYTES_PER_CELL);
        present[b * Vkzg.CELL_PROOFS_PER_BLOB + column] = 1;
      }
    }

    final long recoverStart = System.nanoTime();
    final byte[] recoveredCells = Vkzg.recoverCellsBatch(cells, present, blobCount);
    final long recoverElapsedMs = (System.nanoTime() - recoverStart) / 1_000_000;
    LOG.info(
        "vkzg recovered cells for {} blob(s) in {} ms ({} ms/blob)",
        blobCount,
        recoverElapsedMs,
        String.format("%.2f", recoverElapsedMs / (double) blobCount));

    final int cellsPerBlobBytes = Vkzg.CELL_PROOFS_PER_BLOB * Vkzg.BYTES_PER_CELL;
    final byte[] blobs = new byte[blobCount * Vkzg.BYTES_PER_BLOB];
    for (int b = 0; b < blobCount; b++) {
      // The first half of the recovered cells (in column order) is the original blob,
      // concatenated -- see MiscHelpersFulu.recoverMatrix / BlobReconstructor.constructBlob for
      // the same trick.
      System.arraycopy(
          recoveredCells,
          b * cellsPerBlobBytes,
          blobs,
          b * Vkzg.BYTES_PER_BLOB,
          Vkzg.BYTES_PER_BLOB);
    }

    final long proofsStart = System.nanoTime();
    final byte[] proofs = Vkzg.computeCellKzgProofsBatch(blobs, blobCount);
    final long proofsElapsedMs = (System.nanoTime() - proofsStart) / 1_000_000;
    LOG.info(
        "vkzg computed cell KZG proofs for {} blob(s) in {} ms ({} ms/blob)",
        blobCount,
        proofsElapsedMs,
        String.format("%.2f", proofsElapsedMs / (double) blobCount));

    final List<KZGCell> allCells = KZGCell.splitBytes(Bytes.wrap(recoveredCells));
    final List<KZGProof> allProofs = KZGProof.splitBytes(Bytes.wrap(proofs));

    final List<List<KZGCellAndProof>> result = new ArrayList<>(blobCount);
    for (int b = 0; b < blobCount; b++) {
      final List<KZGCellAndProof> blobCellsAndProofs = new ArrayList<>(Vkzg.CELL_PROOFS_PER_BLOB);
      for (int c = 0; c < Vkzg.CELL_PROOFS_PER_BLOB; c++) {
        final int i = b * Vkzg.CELL_PROOFS_PER_BLOB + c;
        blobCellsAndProofs.add(new KZGCellAndProof(allCells.get(i), allProofs.get(i)));
      }
      result.add(blobCellsAndProofs);
    }
    return result;
  }
}
