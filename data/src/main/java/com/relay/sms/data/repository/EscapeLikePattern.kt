/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.repository

/**
 * Feedback round 5 — fonction utilitaire pure extraite de
 * [ConversationRepositoryImpl] pour permettre des tests unitaires directs sans
 * instancier le repo (qui requiert N dépendances Hilt). Même motif que
 * [escapeFtsQuery].
 *
 * SQLite `LIKE` traite `%`, `_` et `\` comme des jokers / échappement : une
 * query utilisateur les contenant matcherait n'importe quoi (ou rien) au lieu
 * du texte littéral. On les neutralise pour que la recherche de fil matche le
 * texte tel que tapé. Mirrors the `ESCAPE '\'` clause in
 * [com.relay.sms.data.local.db.dao.MessageDao.observeSearchIdsInConversation].
 *
 * `internal` car ce n'est pas une API publique du module — uniquement consommé par le
 * repository et ses tests.
 */
internal fun escapeLikePattern(input: String): String =
    input
        .replace("""\""", """\\""")
        .replace("""%""", """\%""")
        .replace("""_""", """\_""")
