"""
app.py — PyQt6 интерфейс ведущего.
"""

import sys
from PyQt6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QPushButton, QTextEdit, QFrame, QScrollArea, QGridLayout,
    QProgressBar, QSizePolicy
)
from PyQt6.QtCore import Qt, QSize
from PyQt6.QtGui import QFont

from model import make_default_game, Action, ActionType, GameState, Country, City, MAX_LEVEL
from engine import add_action, remove_action, resolve_turn, ActionError


# ─── Палитра ─────────────────────────────────────────────────────────────────

BG       = "#212135"
BG_PANEL = "#1a1a2e"
BG_CARD  = "#1e1e35"
BORDER   = "#2a2a4a"
TEXT     = "#dce1f0"
DIM      = "#5a6080"
ACCENT   = "#e94560"
BLUE     = "#4fc3f7"
GREEN    = "#4caf50"
YELLOW   = "#ffd54f"
RED      = "#ef5350"
DEAD_TEXT = "#444"

GLOBAL_STYLE = f"""
* {{ font-family: 'Consolas', 'Courier New', monospace; color: {TEXT}; }}
QMainWindow, QWidget {{ background: {BG}; }}
QTextEdit {{
    background: {BG_PANEL}; border: 1px solid {BORDER};
    border-radius: 4px; font-size: 13px;
}}
QScrollArea {{ border: none; background: transparent; }}
QProgressBar {{
    background: {BG_CARD}; border: 1px solid {BORDER};
    border-radius: 3px; text-align: center; font-size: 12px;
}}
QProgressBar::chunk {{
    border-radius: 2px;
    background: qlineargradient(x1:0,y1:0,x2:1,y2:0,
        stop:0 {GREEN}, stop:0.55 {YELLOW}, stop:1 {RED});
}}
"""

def _btn(bg, fg="white", hover=None, size=None, radius=4, bold=False, fs=13, greyed_when_disabled=False):
    hover = hover or bg
    w = "bold" if bold else "normal"
    dim = f"min-width:{size}px;min-height:{size}px;max-width:{size}px;max-height:{size}px;" if size else ""
    disabled = f"QPushButton:disabled {{ background:#252535;color:{DIM}; }}" if greyed_when_disabled else ""
    return f"""
    QPushButton {{ background:{bg};color:{fg};border:none;border-radius:{radius}px;
        font-size:{fs}px;font-weight:{w};{dim} }}
    QPushButton:hover {{ background:{hover}; }}
    {disabled}
    """


# ─── CityRow ─────────────────────────────────────────────────────────────────

class CityRow(QWidget):
    def __init__(self, city: City, country: Country, state: GameState, app: "MainWindow"):
        super().__init__()
        self.city = city
        self.country = country
        self.state = state
        self.app = app
        self._build()

    def _build(self):
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 1, 0, 1)
        lay.setSpacing(4)

        self.btn_name = QPushButton()
        self.btn_name.setFlat(True)
        self.btn_name.setFixedHeight(24)
        self.btn_name.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.btn_name.setCursor(Qt.CursorShape.PointingHandCursor)
        self.btn_name.clicked.connect(self._on_name_clicked)
        lay.addWidget(self.btn_name, stretch=1)

        self.lbl_level = QLabel()
        self.lbl_level.setFixedWidth(34)
        self.lbl_level.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.lbl_level.setFont(QFont("Consolas", 10))
        lay.addWidget(self.lbl_level)

        self.btn_up = QPushButton("⬆")
        self.btn_up.setFixedSize(QSize(26, 26))
        self.btn_up.setCursor(Qt.CursorShape.PointingHandCursor)
        self.btn_up.clicked.connect(self._upgrade)
        lay.addWidget(self.btn_up)

        # Кнопка щита — становится зелёной когда щит стоит, иконка не добавляется
        self.btn_sh = QPushButton("🛡")
        self.btn_sh.setFixedSize(QSize(26, 26))
        self.btn_sh.setCursor(Qt.CursorShape.PointingHandCursor)
        self.btn_sh.clicked.connect(self._shield)
        lay.addWidget(self.btn_sh)

        self.refresh()

    def refresh(self):
        city = self.city
        alive = city.isalive
        launch_mode = self.app.launch_attacker is not None

        # ── Название ──
        if not alive:
            self.btn_name.setText(f"✕ {city.name}")
            self.btn_name.setStyleSheet(f"""
                QPushButton {{ background:transparent; color:{DEAD_TEXT};
                    text-decoration:line-through; text-align:left;
                    padding-left:4px; border:none; font-size:13px; }}
            """)
            self.btn_name.setEnabled(False)
        elif launch_mode:
            self.btn_name.setText(f"🎯 {city.name}")
            self.btn_name.setStyleSheet(f"""
                QPushButton {{ background:#3a1a1a; color:{RED};
                    text-align:left; padding-left:4px;
                    border:1px solid {RED}; border-radius:3px; font-size:13px; }}
                QPushButton:hover {{ background:{RED}; color:white; }}
            """)
            self.btn_name.setEnabled(True)
        else:
            self.btn_name.setText(f"  {city.name}")
            self.btn_name.setStyleSheet(f"""
                QPushButton {{ background:transparent; color:{TEXT};
                    text-align:left; padding-left:4px; border:none; font-size:13px; }}
                QPushButton:hover {{ background:transparent; }}
            """)
            self.btn_name.setEnabled(False)

        # ── Уровень ──
        self.lbl_level.setText(f"ур.{city.level}")
        self.lbl_level.setStyleSheet(f"color:{BLUE if alive else DEAD_TEXT};")

        # ── Кнопки — скрываем в режиме атаки ──
        show = not launch_mode
        self.btn_up.setVisible(show)
        self.btn_sh.setVisible(show)

        if show:
            can_up = alive and city.level < MAX_LEVEL
            self.btn_up.setEnabled(can_up)
            self.btn_up.setStyleSheet(
                _btn("#2a3a2a", hover="#3a5a3a", size=26, fs=13) if can_up
                else _btn("#222", size=26, fs=13, greyed_when_disabled=True)
            )

            if not alive:
                self.btn_sh.setEnabled(False)
                self.btn_sh.setStyleSheet(_btn("#222", size=26, fs=13, greyed_when_disabled=True))
            elif city.shield:
                # Щит стоит — зелёная, задизейблена (цвет должен остаться зелёным)
                self.btn_sh.setEnabled(False)
                self.btn_sh.setStyleSheet(_btn(GREEN, size=26, fs=13))
            else:
                self.btn_sh.setEnabled(True)
                self.btn_sh.setStyleSheet(_btn("#2a3a4a", hover="#37474f", size=26, fs=13))

    def _on_name_clicked(self):
        if self.app.launch_attacker:
            self.app.queue_action(Action(ActionType.LAUNCH, self.app.launch_attacker.name, self.city.name))
            self.app.launch_attacker = None
            self.app.refresh_all()

    def _upgrade(self):
        self.app.queue_action(Action(ActionType.UPGRADE, self.country.name, self.city.name))

    def _shield(self):
        self.app.queue_action(Action(ActionType.SHIELD, self.country.name, self.city.name))


# ─── CountryCard ─────────────────────────────────────────────────────────────

class CountryCard(QFrame):
    def __init__(self, country: Country, state: GameState, app: "MainWindow"):
        super().__init__()
        self.country = country
        self.state = state
        self.app = app
        self.city_rows: list[CityRow] = []
        self._build()

    def _build(self):
        self.setStyleSheet(f"""
            QFrame {{ background:{BG_CARD}; border:1px solid {BORDER}; border-radius:8px; }}
        """)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(10, 8, 10, 8)
        lay.setSpacing(5)

        # ── Название ──
        self.lbl_name = QLabel(self.country.name)
        self.lbl_name.setFont(QFont("Consolas", 13, QFont.Weight.Bold))
        self.lbl_name.setStyleSheet(f"color:{ACCENT}; background:transparent; border:none;")
        lay.addWidget(self.lbl_name)

        # ── Финансы ──
        self.lbl_money = QLabel()
        self.lbl_money.setFont(QFont("Consolas", 12))
        self.lbl_money.setStyleSheet(f"color:{DIM}; background:transparent; border:none;")
        lay.addWidget(self.lbl_money)

        # ── Кнопки страны ──
        btn_row = QHBoxLayout()
        btn_row.setSpacing(6)

        # 🔬 Технология
        self.btn_tech = QPushButton("🔬")
        self.btn_tech.setFixedSize(QSize(36, 36))
        self.btn_tech.setCursor(Qt.CursorShape.PointingHandCursor)
        self.btn_tech.clicked.connect(self._research)
        btn_row.addWidget(self.btn_tech)

        # 💣 Производство + счётчик
        self.btn_bomb = QPushButton("💣")
        self.btn_bomb.setFixedSize(QSize(36, 36))
        self.btn_bomb.setCursor(Qt.CursorShape.PointingHandCursor)
        self.btn_bomb.clicked.connect(self._produce)
        btn_row.addWidget(self.btn_bomb)

        self.lbl_bombs = QLabel()
        self.lbl_bombs.setFont(QFont("Consolas", 13))
        self.lbl_bombs.setStyleSheet(f"color:{TEXT}; background:transparent; border:none;")
        btn_row.addWidget(self.lbl_bombs)

        # 🌿 Экология
        self.btn_eco = QPushButton("🌿")
        self.btn_eco.setFixedSize(QSize(36, 36))
        self.btn_eco.setCursor(Qt.CursorShape.PointingHandCursor)
        self.btn_eco.clicked.connect(self._ecology)
        btn_row.addWidget(self.btn_eco)

        btn_row.addStretch()
        lay.addLayout(btn_row)

        # ── Разделитель ──
        sep = QFrame()
        sep.setFrameShape(QFrame.Shape.HLine)
        sep.setStyleSheet(f"background:{BORDER}; border:none; max-height:1px;")
        lay.addWidget(sep)

        # ── Города ──
        for city in self.country.cities:
            row = CityRow(city, self.country, self.state, self.app)
            self.city_rows.append(row)
            lay.addWidget(row)

        # ── Ракета ──
        self.btn_launch = QPushButton("🚀  УДАР")
        self.btn_launch.setFixedHeight(32)
        self.btn_launch.setCursor(Qt.CursorShape.PointingHandCursor)
        self.btn_launch.clicked.connect(self._toggle_launch)
        lay.addWidget(self.btn_launch)

        lay.addStretch()  # прижимаем контент к верху при растяжении строки
        self.refresh()

    def refresh(self):
        c = self.country
        alive = c.is_alive
        is_attacker = self.app.launch_attacker is c

        if not alive:
            self.lbl_name.setStyleSheet(
                f"color:{DEAD_TEXT}; background:transparent; border:none; text-decoration:line-through;"
            )

        self.lbl_money.setText(f"💰 {c.balance}$   📈 +{c.income}$/ход   ⭐ {c.score}")

        # 🔬 Технология — серая / жёлтая (в процессе) / зелёная (готова)
        if c.tech:
            self.btn_tech.setStyleSheet(_btn(GREEN, size=36, fs=16))
            self.btn_tech.setEnabled(False)
            self.btn_tech.setToolTip("Технология изучена")
        elif c.tech_in_research:
            self.btn_tech.setStyleSheet(_btn(YELLOW, fg="#111", size=36, fs=16))
            self.btn_tech.setEnabled(False)
            self.btn_tech.setToolTip("Исследование в процессе")
        else:
            self.btn_tech.setStyleSheet(_btn("#2a2a3a", hover="#3a3a5a", size=36, fs=16))
            self.btn_tech.setEnabled(alive)
            self.btn_tech.setToolTip("Изучить технологию (500$)")

        # 💣 Бомбы
        prod = c.bombs_in_production
        if prod > 0:
            self.btn_bomb.setStyleSheet(_btn(BLUE, size=36, fs=16))
            self.lbl_bombs.setText(f"{c.bombs} +{prod}")
        else:
            self.btn_bomb.setStyleSheet(_btn("#2a2a3a", hover="#3a3a5a", size=36, fs=16))
            self.lbl_bombs.setText(str(c.bombs))
        self.btn_bomb.setEnabled(alive and c.tech)
        if not (alive and c.tech):
            self.btn_bomb.setStyleSheet(_btn("#2a2a3a", size=36, fs=16, greyed_when_disabled=True))
        self.btn_bomb.setToolTip("Произвести бомбу (150$)" if c.tech else "Нужна технология")

        # 🌿 Экология
        self.btn_eco.setStyleSheet(_btn("#1a2a1a", hover="#2a4a2a", size=36, fs=16, greyed_when_disabled=not alive))
        self.btn_eco.setEnabled(alive)

        # 🚀 Ракета
        if not alive or c.bombs < 1:
            self.btn_launch.setStyleSheet(_btn("#2a1a1a", fg=DEAD_TEXT, fs=13))
            self.btn_launch.setEnabled(False)
            self.btn_launch.setText("🚀  УДАР")
        elif is_attacker:
            self.btn_launch.setStyleSheet(_btn(RED, hover="#ff6060", fs=13, bold=True))
            self.btn_launch.setEnabled(True)
            self.btn_launch.setText("🚀  ВЫБИРАЮ ЦЕЛЬ...")
        else:
            self.btn_launch.setStyleSheet(_btn("#3a1a1a", hover=RED, fg=RED, fs=13, bold=True))
            self.btn_launch.setEnabled(True)
            self.btn_launch.setText("🚀  УДАР")

        for row in self.city_rows:
            row.refresh()

    def _research(self):
        self.app.queue_action(Action(ActionType.RESEARCH, self.country.name))

    def _produce(self):
        self.app.queue_action(Action(ActionType.PRODUCE, self.country.name))

    def _ecology(self):
        self.app.queue_action(Action(ActionType.ECOLOGY, self.country.name))

    def _toggle_launch(self):
        self.app.launch_attacker = None if self.app.launch_attacker is self.country else self.country
        self.app.refresh_all()


# ─── Панель лога + случайный ивент ──────────────────────────────────────────

class LogPanel(QFrame):
    def __init__(self):
        super().__init__()
        self.setStyleSheet(f"""
            QFrame {{ background:{BG_CARD}; border:1px solid {BORDER}; border-radius:8px; }}
        """)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(10, 8, 10, 8)
        lay.setSpacing(6)

        lbl = QLabel("ЛОГ СОБЫТИЙ")
        lbl.setFont(QFont("Consolas", 11, QFont.Weight.Bold))
        lbl.setStyleSheet(f"color:{DIM}; border:none;")
        lay.addWidget(lbl)

        self.log_text = QTextEdit()
        self.log_text.setReadOnly(True)
        self.log_text.setMinimumHeight(0)
        lay.addWidget(self.log_text, stretch=1)

        self.btn_event = QPushButton("🎲  СЛУЧАЙНЫЙ ИВЕНТ")
        self.btn_event.setFixedHeight(36)
        self.btn_event.setCursor(Qt.CursorShape.PointingHandCursor)
        self.btn_event.setStyleSheet(_btn("#2a2a1a", hover="#3a3a2a", fg=YELLOW, fs=13, bold=True))
        self.btn_event.clicked.connect(self._on_event)
        lay.addWidget(self.btn_event)

    def _on_event(self):
        # TODO: случайные ивенты
        pass

    def append(self, text: str):
        self.log_text.append(text)
        self.log_text.verticalScrollBar().setValue(
            self.log_text.verticalScrollBar().maximum()
        )


# ─── QueueRow ────────────────────────────────────────────────────────────────

ACTION_LABELS = {
    ActionType.UPGRADE:  "⬆ Улучшить",
    ActionType.SHIELD:   "🛡 Щит на",
    ActionType.RESEARCH: "🔬 Исследование",
    ActionType.PRODUCE:  "💣 Производство",
    ActionType.ECOLOGY:  "🌿 Экология",
    ActionType.LAUNCH:   "🚀 Удар по",
}

class QueueRow(QWidget):
    def __init__(self, action: Action, index: int, app: "MainWindow"):
        super().__init__()
        self.index = index
        self.app = app
        lay = QHBoxLayout(self)
        lay.setContentsMargins(6, 2, 6, 2)

        text = f"{action.country_name}  →  {ACTION_LABELS[action.action_type]}"
        if action.target_city_name:
            text += f" {action.target_city_name}"
        lbl = QLabel(text)
        lbl.setFont(QFont("Consolas", 12))
        lay.addWidget(lbl)
        lay.addStretch()

        btn = QPushButton("✕")
        btn.setFixedSize(QSize(22, 22))
        btn.setStyleSheet(f"""
            QPushButton {{ background:#2a1a1a; color:{RED}; border:none; border-radius:3px; font-size:13px; }}
            QPushButton:hover {{ background:{RED}; color:white; }}
        """)
        btn.clicked.connect(lambda: self.app.remove_queued_action(self.index))
        lay.addWidget(btn)


# ─── MainWindow ──────────────────────────────────────────────────────────────

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.state = make_default_game()
        self.country_cards: list[CountryCard] = []
        self.launch_attacker: Country | None = None

        self.setWindowTitle("Мировое Господство")
        self.setMinimumSize(1280, 820)
        self.setStyleSheet(GLOBAL_STYLE)
        self._build()
        self.refresh_all()

    def _build(self):
        root = QWidget()
        self.setCentralWidget(root)
        lay = QVBoxLayout(root)
        lay.setContentsMargins(10, 10, 10, 10)
        lay.setSpacing(8)

        lay.addWidget(self._build_topbar())

        body = QHBoxLayout()
        body.setSpacing(10)

        # ── Сетка 3×2: 5 карточек + лог в шестой ячейке ──
        grid_w = QWidget()
        grid = QGridLayout(grid_w)
        grid.setSpacing(8)
        grid.setRowStretch(0, 1)
        grid.setRowStretch(1, 1)
        grid.setColumnStretch(0, 1)
        grid.setColumnStretch(1, 1)
        grid.setColumnStretch(2, 1)

        for i, country in enumerate(self.state.countries):
            card = CountryCard(country, self.state, self)
            card.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
            self.country_cards.append(card)
            grid.addWidget(card, i // 3, i % 3)

        self.log_panel = LogPanel()
        grid.addWidget(self.log_panel, 1, 2)

        body.addWidget(grid_w, stretch=3)

        # ── Правая панель — очередь действий ──
        body.addWidget(self._build_queue_panel(), stretch=1)

        lay.addLayout(body, stretch=1)

    def _build_topbar(self) -> QWidget:
        w = QWidget()
        w.setStyleSheet(f"background:{BG_PANEL}; border-radius:6px;")
        lay = QHBoxLayout(w)
        lay.setContentsMargins(14, 8, 14, 8)

        self.lbl_turn = QLabel()
        self.lbl_turn.setFont(QFont("Consolas", 15, QFont.Weight.Bold))
        self.lbl_turn.setStyleSheet(f"color:{ACCENT};")
        lay.addWidget(self.lbl_turn)

        lay.addSpacing(16)
        lay.addWidget(QLabel("ЭКОЛОГИЯ"))

        self.eco_bar = QProgressBar()
        self.eco_bar.setRange(0, 100)
        self.eco_bar.setFixedSize(QSize(180, 20))
        lay.addWidget(self.eco_bar)

        self.lbl_eco = QLabel()
        self.lbl_eco.setFont(QFont("Consolas", 13))
        lay.addWidget(self.lbl_eco)

        lay.addStretch()

        self.lbl_mode = QLabel()
        self.lbl_mode.setFont(QFont("Consolas", 13))
        self.lbl_mode.setStyleSheet(f"color:{RED};")
        lay.addWidget(self.lbl_mode)

        lay.addSpacing(16)

        self.btn_resolve = QPushButton("▶  ПРИМЕНИТЬ ХОД")
        self.btn_resolve.setFixedHeight(36)
        self.btn_resolve.setCursor(Qt.CursorShape.PointingHandCursor)
        self.btn_resolve.setStyleSheet(_btn(GREEN, fg="#111", hover="#66bb6a", fs=14, bold=True))
        self.btn_resolve.clicked.connect(self._on_resolve)
        lay.addWidget(self.btn_resolve)

        return w

    def _build_queue_panel(self) -> QWidget:
        w = QFrame()
        w.setStyleSheet(f"QFrame {{ background:{BG_CARD}; border:1px solid {BORDER}; border-radius:8px; }}")
        lay = QVBoxLayout(w)
        lay.setContentsMargins(8, 8, 8, 8)
        lay.setSpacing(6)

        lbl = QLabel("ОЧЕРЕДЬ ДЕЙСТВИЙ")
        lbl.setFont(QFont("Consolas", 11, QFont.Weight.Bold))
        lbl.setStyleSheet(f"color:{DIM}; border:none;")
        lay.addWidget(lbl)

        self.queue_widget = QWidget()
        self.queue_widget.setStyleSheet("background:transparent;")
        self.queue_layout = QVBoxLayout(self.queue_widget)
        self.queue_layout.setContentsMargins(0, 0, 0, 0)
        self.queue_layout.setSpacing(1)
        self.queue_layout.addStretch()

        scroll = QScrollArea()
        scroll.setWidget(self.queue_widget)
        scroll.setWidgetResizable(True)
        lay.addWidget(scroll, stretch=1)

        return w

    # ── Очередь ──────────────────────────────────────────────────────────────

    def queue_action(self, action: Action):
        try:
            add_action(self.state, action)
            self._refresh_queue()
            self.refresh_all()
        except ActionError as e:
            self.log_panel.append(f"⚠ {e}")

    def remove_queued_action(self, index: int):
        remove_action(self.state, index)
        self._refresh_queue()

    def _refresh_queue(self):
        while self.queue_layout.count() > 1:
            item = self.queue_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        for i, action in enumerate(self.state.pending_actions):
            self.queue_layout.insertWidget(i, QueueRow(action, i, self))

    # ── Применить ход ────────────────────────────────────────────────────────

    def _on_resolve(self):
        self.launch_attacker = None
        for line in resolve_turn(self.state):
            self.log_panel.append(line)
        self.log_panel.append("")
        self.refresh_all()
        if self.state.game_over:
            self.btn_resolve.setEnabled(False)

    # ── Обновление ───────────────────────────────────────────────────────────

    def refresh_all(self):
        self.lbl_turn.setText(f"ХОД {self.state.turn}")
        self.eco_bar.setValue(self.state.ecology)
        self.lbl_eco.setText(f"{self.state.ecology}%")
        eco_c = GREEN if self.state.ecology > 60 else (YELLOW if self.state.ecology > 30 else RED)
        self.lbl_eco.setStyleSheet(f"color:{eco_c};")

        self.lbl_mode.setText(
            f"🚀 РЕЖИМ АТАКИ: {self.launch_attacker.name}  [Esc — отмена]"
            if self.launch_attacker else ""
        )

        for card in self.country_cards:
            card.refresh()

    def keyPressEvent(self, event):
        if event.key() == Qt.Key.Key_Escape and self.launch_attacker:
            self.launch_attacker = None
            self.refresh_all()


# ─── Запуск ──────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    w = MainWindow()
    w.show()
    sys.exit(app.exec())