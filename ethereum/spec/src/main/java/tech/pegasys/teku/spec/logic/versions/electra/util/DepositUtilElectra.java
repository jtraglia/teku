/*
 * Copyright Consensys Software Inc., 2024
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

package tech.pegasys.teku.spec.logic.versions.electra.util;

import static tech.pegasys.teku.infrastructure.unsigned.UInt64.ZERO;
import static tech.pegasys.teku.spec.config.SpecConfig.FAR_FUTURE_EPOCH;
import static tech.pegasys.teku.spec.logic.common.block.AbstractBlockProcessor.depositSignatureVerifier;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.bls.impl.BlsException;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszByte;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszUInt64;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.config.SpecConfig;
import tech.pegasys.teku.spec.constants.Domain;
import tech.pegasys.teku.spec.datastructures.operations.DepositMessage;
import tech.pegasys.teku.spec.datastructures.state.Validator;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.MutableBeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.versions.electra.MutableBeaconStateElectra;
import tech.pegasys.teku.spec.logic.common.helpers.BeaconStateAccessors;
import tech.pegasys.teku.spec.logic.common.helpers.MiscHelpers;
import tech.pegasys.teku.spec.logic.versions.electra.helpers.BeaconStateAccessorsElectra;

public class DepositUtilElectra {
  protected final SpecConfig specConfig;
  protected final MiscHelpers miscHelpers;
  protected final BeaconStateAccessors stateAccessors;

  public DepositUtilElectra(
      final SpecConfig specConfig,
      final MiscHelpers miscHelpers,
      final BeaconStateAccessors stateAccessors) {
    this.specConfig = specConfig;
    this.miscHelpers = miscHelpers;
    this.stateAccessors = stateAccessors;
  }

  private Validator getValidatorFromDeposit(
      final BLSPublicKey pubkey, final Bytes32 withdrawalCredentials, final UInt64 amount) {
    final Validator validator =
        new Validator(
            pubkey,
            withdrawalCredentials,
            ZERO,
            false,
            FAR_FUTURE_EPOCH,
            FAR_FUTURE_EPOCH,
            FAR_FUTURE_EPOCH,
            FAR_FUTURE_EPOCH);

    final UInt64 maxEffectiveBalance =
        BeaconStateAccessorsElectra.required(stateAccessors)
            .getValidatorMaxEffectiveBalance(validator);
    final UInt64 validatorEffectiveBalance =
        amount
            .minusMinZero(amount.mod(specConfig.getEffectiveBalanceIncrement()))
            .min(maxEffectiveBalance);

    return validator.withEffectiveBalance(validatorEffectiveBalance);
  }

  public void addValidatorToRegistry(
      final MutableBeaconState state,
      final BLSPublicKey pubkey,
      final Bytes32 withdrawalCredentials,
      final UInt64 amount) {
    final Validator validator = getValidatorFromDeposit(pubkey, withdrawalCredentials, amount);

    final MutableBeaconStateElectra stateElectra = MutableBeaconStateElectra.required(state);
    stateElectra.getValidators().append(validator);
    stateElectra.getBalances().appendElement(amount);
    stateElectra.getPreviousEpochParticipation().append(SszByte.ZERO);
    stateElectra.getCurrentEpochParticipation().append(SszByte.ZERO);
    stateElectra.getInactivityScores().append(SszUInt64.ZERO);
  }

  public Bytes computeDepositSigningRoot(
      final BLSPublicKey pubkey, final Bytes32 withdrawalCredentials, final UInt64 amount) {
    final Bytes32 domain = miscHelpers.computeDomain(Domain.DEPOSIT);
    final DepositMessage depositMessage = new DepositMessage(pubkey, withdrawalCredentials, amount);
    return miscHelpers.computeSigningRoot(depositMessage, domain);
  }

  public boolean isValidDepositSignature(
      final BLSPublicKey pubkey,
      final Bytes32 withdrawalCredentials,
      final UInt64 amount,
      final BLSSignature signature) {
    try {
      final Bytes signingRoot = computeDepositSigningRoot(pubkey, withdrawalCredentials, amount);
      return depositSignatureVerifier.verify(pubkey, signingRoot, signature);
    } catch (final BlsException e) {
      return false;
    }
  }
}
