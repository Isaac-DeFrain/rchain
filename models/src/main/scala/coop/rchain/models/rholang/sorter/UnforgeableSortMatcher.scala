package coop.rchain.models.rholang.sorter

import cats.effect.Sync
import cats.implicits._
import coop.rchain.models.GUnforgeable
import coop.rchain.models.GUnforgeable.UnfInstance.{Empty, GDeployerAuthBody, GPrivateBody}

private[sorter] object UnforgeableSortMatcher extends Sortable[GUnforgeable] {
  def sortMatch[F[_]: Sync](unf: GUnforgeable): F[ScoredTerm[GUnforgeable]] =
    unf.unfInstance match {
      case GPrivateBody(gpriv) =>
        ScoredTerm(GUnforgeable(GPrivateBody(gpriv)), Node(Score.PRIVATE, Leaf(gpriv.id))).pure[F]
      case GDeployerAuthBody(auth) =>
        ScoredTerm(
          GUnforgeable(GDeployerAuthBody(auth)),
          Node(Score.DEPLOYER_AUTH, Leaf(auth.publicKey))
        ).pure[F]
      case Empty => ScoredTerm(unf, Node(Score.ABSENT)).pure[F]
    }
}