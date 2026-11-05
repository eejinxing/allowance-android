package com.allowance.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allowance.app.R
import com.allowance.core.ledger.AppData
import com.allowance.core.ledger.LedgerEngine
import com.allowance.core.ledger.WalletAccount
import kotlin.math.roundToInt

/** Base colours the silver texture is blended over: green, blue, violet, graphite. */
private val CardBases = listOf(Color(0xFF07442A), Color(0xFF0E36B8), Color(0xFF3A1F8C), Color(0xFF222222))

@Composable
internal fun WalletAccountDeck(data: AppData, accounts: List<WalletAccount>, pager: PagerState,
    onEdit: (WalletAccount) -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(48.dp).height(CardHeight).dashedBorder(MaterialTheme.colorScheme.outline, 18.dp).clip(RoundedCornerShape(18.dp))
            .pressable(onClickLabel = "Add account", onClick = onAdd), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Add, null, Modifier.size(26.dp))
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).height(CardHeight).testTag("wallet_account_pager"),
            pageSize = PageSize.Fixed(CardWidth), contentPadding = PaddingValues(start = 8.dp), pageSpacing = 8.dp,
            key = { accounts.getOrNull(it)?.id ?: "new-account" }) { index ->
            WalletBankCard(data, accounts.getOrNull(index), index, onEdit, onAdd)
        }
    }
    if (accounts.size > 1) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            repeat(accounts.size.coerceAtMost(7)) { index ->
                val selected = pager.currentPage == index
                val size by animateDpAsState(if (selected) 8.dp else 7.dp, spring(dampingRatio = .6f), label = "dot-size")
                val color by animateColorAsState(if (selected) Ink else MaterialTheme.colorScheme.outlineVariant, label = "dot-color")
                Box(Modifier.padding(horizontal = 4.dp).size(size).clip(CircleShape).background(color))
            }
        }
    }
}

private val CardWidth = 226.dp
private val CardHeight = 138.dp

@Composable
private fun WalletBankCard(data: AppData, account: WalletAccount?, index: Int,
    onEdit: (WalletAccount) -> Unit, onAdd: () -> Unit) {
    var hidden by rememberSaveable(account?.id) { mutableStateOf(false) }
    val base = CardBases[index % CardBases.size]
    val texture = ImageBitmap.imageResource(R.drawable.wallet_card_silver)
    val bank = account?.type == "BANK"
    Column(Modifier.fillMaxSize().testTag("wallet_card_${account?.id ?: "empty"}")
        .pressable(role = Role.Button, onClickLabel = if (account == null) "Add account" else "Edit account") { if (account == null) onAdd() else onEdit(account) }
        .clip(RoundedCornerShape(20.dp))
        .drawBehind {
            drawRect(base)
            drawImage(texture, IntOffset.Zero, IntSize(texture.width, texture.height), IntOffset.Zero,
                IntSize(size.width.roundToInt(), size.height.roundToInt()), blendMode = BlendMode.Overlay)
        }
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (bank) {
            Canvas(Modifier.size(width = 36.dp, height = 22.dp)) {
                drawCircle(Color(0xFFEB001B), size.height / 2, Offset(size.height / 2, size.height / 2))
                drawCircle(Color(0xFFF79E1B).copy(alpha = .92f), size.height / 2, Offset(size.width - size.height / 2, size.height / 2))
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(account?.name ?: "Add your first account", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (account == null) "Bank, e-wallet or cash." else "Available balance", color = Color.White.copy(alpha = .8f), fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WalletMoneyAmount(if (account == null) "Start with one" else if (hidden) "••••••" else cash(LedgerEngine.accountBalance(data, account)),
                maxFontSize = if (account == null) 20.sp else 25.sp, modifier = Modifier.weight(1f))
            if (account != null) IconButton(onClick = { hidden = !hidden }, modifier = Modifier.size(36.dp)) {
                Icon(if (hidden) WalletEyeHidden else WalletEye, if (hidden) "Show balance" else "Hide balance", Modifier.size(22.dp), tint = Color.White)
            } else Icon(AppIcons.Add, null, Modifier.size(24.dp), tint = Color.White)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (account == null) "MYR" else "MYR", color = Color.White.copy(alpha = .85f), fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (account != null && !bank) Canvas(Modifier.size(width = 30.dp, height = 22.dp)) {
                drawRoundRect(Color(0xFFE9E9EE), Offset.Zero, size, CornerRadius(5.dp.toPx()))
                val line = Color(0xFFB4B4BE)
                drawLine(line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.2.dp.toPx())
                drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.2.dp.toPx())
                drawRoundRect(line, Offset(size.width * .3f, size.height * .3f), Size(size.width * .4f, size.height * .4f), CornerRadius(3.dp.toPx()), style = Stroke(1.2.dp.toPx()))
            }
        }
    }
}

@Composable
internal fun WalletAccountActions(transferEnabled: Boolean, balanceEnabled: Boolean,
    onTransfer: () -> Unit, onBalance: () -> Unit, onAccounts: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionPill("Transfer", AppIcons.ArrowUpRight, transferEnabled, true, Modifier.weight(1.08f), onTransfer)
        ActionPill("Adjust", AppIcons.Tune, balanceEnabled, false, Modifier.weight(1f), onBalance)
        Box(Modifier.size(width = 56.dp, height = 54.dp).testTag("wallet_accounts_grid").pressable(onClick = onAccounts).clip(RoundedCornerShape(27.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).semantics { contentDescription = "Show all accounts" }, contentAlignment = Alignment.Center) {
            val ink = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.size(22.dp)) {
                val cell = size.width * .42f
                val gap = size.width - cell
                repeat(2) { row -> repeat(2) { col ->
                    drawRoundRect(ink, Offset(col * gap, row * gap), Size(cell, cell), CornerRadius(3.dp.toPx(), 3.dp.toPx()), style = Stroke(2.dp.toPx()))
                } }
            }
        }
    }
}

@Composable
private fun ActionPill(label: String, icon: ImageVector, enabled: Boolean, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val container = if (filled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainer
    val content = if (filled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    Row(modifier.height(54.dp).pressable(enabled = enabled, onClick = onClick).clip(RoundedCornerShape(27.dp))
        .background(if (enabled) container else container.copy(alpha = .35f)).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) content else content.copy(alpha = .6f))
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (enabled) content else content.copy(alpha = .6f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
    }
}

private fun walletEye(name: String, hidden: Boolean): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    addPath(PathParser().parsePathString("M2 12Q6 5 12 5Q18 5 22 12Q18 19 12 19Q6 19 2 12ZM15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12" +
        if (hidden) "M3 3L21 21" else "").toNodes(), stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}.build()
private val WalletEye = walletEye("WalletEye", false)
private val WalletEyeHidden = walletEye("WalletEyeHidden", true)

@Composable
internal fun WalletMoneyAmount(value: String, maxFontSize: TextUnit, modifier: Modifier = Modifier, color: Color = Color.White) {
    BasicText(value, modifier.fillMaxWidth(), style = TextStyle(fontFeatureSettings = "tnum", color = color, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        fontSize = maxFontSize, letterSpacing = (-.5).sp), maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = maxFontSize, stepSize = 1.sp))
}
