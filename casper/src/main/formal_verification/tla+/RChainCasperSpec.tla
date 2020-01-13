-------------------------- MODULE RChainCasperSpec --------------------------
EXTENDS CBCCasperSpec

\* Synchrony & Fairness
\* Synchrony constraint
\* compute set of all observed senders in messages after a validator's last message
\* - SeenSendersSince(n) == Observed(Later(Latest(n,states[n]),ToSet(unscored_q[n])))
\* restrict validator from sending next message until they've seen sufficient weight/number of later messages
\* - enabling condition for Send action:
\*     Weight(SeenSendersSince(n)) > weightSynchronyThreshold /\ Cardinality(SeenSendersSince(n)) > numberSynchronyThreshold

\* ultimately prove (with appropriate refinement mapping):
\* THEOREM RChainCasperSpec => CBCCasperSpec

=============================================================================
