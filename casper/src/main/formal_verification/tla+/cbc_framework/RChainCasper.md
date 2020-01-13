# RChain's Casper Implementation

Features of RChain's Casper implementation:
- Latest estimate driven estimator: multi-parent GHOST
- GHOST fork-choice rule (https://github.com/ethereum/wiki/wiki/White-Paper#modified-ghost-implementation)
- validator rotation
- slashing/equivocation
- post-unbonding stake holding time i.e. quarantine (prevents long range attack)
- safe from army of ants attack/byzantine generals problem/whales (centralization)
- block production rewards/transaction fees
