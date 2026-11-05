package com.botaovolume

import android.content.Context
import android.content.SharedPreferences

/** Todas as configurações do botão ficam salvas aqui. */
object Prefs {
    private const val NAME = "configuracoes"

    const val SIZE = "tamanho"          // em dp
    const val OPACITY = "opacidade"     // 20 a 100 (%)
    const val COLOR = "cor"
    const val DOUBLE_TAP = "toque_duplo"
    const val FIXED = "fixo"
    const val POS_X = "pos_x"
    const val POS_Y = "pos_y"

    const val DEFAULT_SIZE = 56
    const val DEFAULT_OPACITY = 80
    val DEFAULT_COLOR = 0xFF1E88E5.toInt()

    val COLORS = intArrayOf(
        0xFF1E88E5.toInt(), // azul
        0xFF43A047.toInt(), // verde
        0xFFE53935.toInt(), // vermelho
        0xFF8E24AA.toInt(), // roxo
        0xFFFB8C00.toInt(), // laranja
        0xFFE91E63.toInt(), // rosa
        0xFF757575.toInt(), // cinza
        0xFF212121.toInt()  // preto
    )

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
