from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QObject, QThread, Signal, Slot
from PySide6.QtWidgets import (
    QApplication,
    QComboBox,
    QFileDialog,
    QFormLayout,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QSpinBox,
    QDoubleSpinBox,
    QTableWidget,
    QTableWidgetItem,
    QTextEdit,
    QVBoxLayout,
    QWidget,
    QCheckBox,
)

from .ffmpeg import package_video
from .models import DEFAULT_RENDITIONS, PackageOptions, Rendition


class PackagerWorker(QObject):
    progress = Signal(float)
    log = Signal(str)
    finished = Signal()
    failed = Signal(str)

    def __init__(self, options: PackageOptions) -> None:
        super().__init__()
        self.options = options

    @Slot()
    def run(self) -> None:
        try:
            package_video(
                self.options,
                progress_callback=lambda value: self.progress.emit(value),
                log_callback=lambda message: self.log.emit(message),
            )
            self.finished.emit()
        except Exception as exc:
            self.failed.emit(str(exc))


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("VR360 Video Packager")
        self.resize(980, 760)

        self._thread: QThread | None = None
        self._worker: PackagerWorker | None = None
        self.profile_checks: list[QCheckBox] = []

        root = QWidget(self)
        self.setCentralWidget(root)
        layout = QVBoxLayout(root)

        form = QFormLayout()

        self.input_edit = QLineEdit()
        input_row = QHBoxLayout()
        input_row.addWidget(self.input_edit)
        input_button = QPushButton("Выбрать...")
        input_button.clicked.connect(self._choose_input)
        input_row.addWidget(input_button)
        form.addRow("Исходное видео:", input_row)

        self.output_edit = QLineEdit()
        output_row = QHBoxLayout()
        output_row.addWidget(self.output_edit)
        output_button = QPushButton("Папка...")
        output_button.clicked.connect(self._choose_output)
        output_row.addWidget(output_button)
        form.addRow("Папка результата:", output_row)

        self.projection_combo = QComboBox()
        self.projection_combo.addItem("360° equirectangular", "equirect360")
        self.projection_combo.addItem("180° equirectangular", "equirect180")
        form.addRow("Проекция:", self.projection_combo)

        self.layout_combo = QComboBox()
        self.layout_combo.addItem("Mono", "mono")
        self.layout_combo.addItem("Stereo SBS", "stereo_horiz")
        self.layout_combo.addItem("Stereo Top/Bottom", "stereo_vert")
        form.addRow("Стерео:", self.layout_combo)

        self.encoder_combo = QComboBox()
        self.encoder_combo.addItem("CPU — libx264", "libx264")
        self.encoder_combo.addItem("NVIDIA — h264_nvenc", "h264_nvenc")
        self.encoder_combo.addItem("AMD — h264_amf", "h264_amf")
        form.addRow("Кодировщик:", self.encoder_combo)

        self.segment_spin = QDoubleSpinBox()
        self.segment_spin.setRange(0.5, 10.0)
        self.segment_spin.setDecimals(1)
        self.segment_spin.setSingleStep(0.5)
        self.segment_spin.setValue(2.0)
        self.segment_spin.setSuffix(" сек")
        form.addRow("Длина сегмента:", self.segment_spin)

        self.audio_spin = QSpinBox()
        self.audio_spin.setRange(64, 320)
        self.audio_spin.setValue(128)
        self.audio_spin.setSuffix(" kbps")
        form.addRow("AAC аудио:", self.audio_spin)

        layout.addLayout(form)

        layout.addWidget(QLabel("Профили качества:"))

        self.table = QTableWidget(len(DEFAULT_RENDITIONS), 4)
        self.table.setHorizontalHeaderLabels(["Вкл.", "Профиль", "Разрешение", "Битрейт, Mbps"])
        self.table.verticalHeader().setVisible(False)
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)

        for row, rendition in enumerate(DEFAULT_RENDITIONS):
            checkbox = QCheckBox()
            checkbox.setChecked(True)
            holder = QWidget()
            holder_layout = QHBoxLayout(holder)
            holder_layout.setContentsMargins(0, 0, 0, 0)
            holder_layout.addStretch(1)
            holder_layout.addWidget(checkbox)
            holder_layout.addStretch(1)
            self.table.setCellWidget(row, 0, holder)
            self.profile_checks.append(checkbox)

            label_item = QTableWidgetItem(rendition.label)
            label_item.setData(256, rendition.id)
            self.table.setItem(row, 1, label_item)

            resolution_item = QTableWidgetItem(f"{rendition.width}x{rendition.height}")
            resolution_item.setData(256, (rendition.width, rendition.height))
            self.table.setItem(row, 2, resolution_item)

            bitrate_item = QTableWidgetItem(f"{rendition.bitrate_mbps:g}")
            self.table.setItem(row, 3, bitrate_item)

        layout.addWidget(self.table)

        buttons = QHBoxLayout()
        self.start_button = QPushButton("ПОДГОТОВИТЬ ВИДЕО")
        self.start_button.setMinimumHeight(44)
        self.start_button.clicked.connect(self._start)
        buttons.addWidget(self.start_button)

        self.open_button = QPushButton("Открыть папку")
        self.open_button.clicked.connect(self._open_output)
        buttons.addWidget(self.open_button)
        layout.addLayout(buttons)

        self.progress = QProgressBar()
        self.progress.setRange(0, 1000)
        self.progress.setValue(0)
        layout.addWidget(self.progress)

        self.status_label = QLabel("Готово к работе")
        layout.addWidget(self.status_label)

        self.log = QTextEdit()
        self.log.setReadOnly(True)
        layout.addWidget(self.log, 1)

    def _choose_input(self) -> None:
        filename, _ = QFileDialog.getOpenFileName(
            self,
            "Выберите исходное видео",
            "",
            "Video (*.mp4 *.mov *.mkv *.m4v *.ts);;All files (*.*)",
        )
        if not filename:
            return

        self.input_edit.setText(filename)

        source = Path(filename)
        if not self.output_edit.text().strip():
            self.output_edit.setText(str(source.parent / f"{source.stem}_hls"))

    def _choose_output(self) -> None:
        directory = QFileDialog.getExistingDirectory(
            self,
            "Выберите папку результата",
            self.output_edit.text().strip() or "",
        )
        if directory:
            self.output_edit.setText(directory)

    def _selected_renditions(self) -> list[Rendition]:
        renditions: list[Rendition] = []

        for row in range(self.table.rowCount()):
            checkbox = self.profile_checks[row]
            if not checkbox.isChecked():
                continue

            label_item = self.table.item(row, 1)
            resolution_item = self.table.item(row, 2)
            bitrate_item = self.table.item(row, 3)

            rendition_id = str(label_item.data(256))
            label = label_item.text().strip()
            width, height = resolution_item.data(256)

            try:
                bitrate = float(bitrate_item.text().replace(",", "."))
            except ValueError as exc:
                raise RuntimeError(f"Некорректный битрейт в строке {row + 1}") from exc

            if bitrate <= 0:
                raise RuntimeError(f"Битрейт должен быть больше нуля: {label}")

            renditions.append(
                Rendition(
                    id=rendition_id,
                    label=label,
                    width=int(width),
                    height=int(height),
                    bitrate_mbps=bitrate,
                )
            )

        return renditions

    def _start(self) -> None:
        if self._thread is not None:
            return

        input_text = self.input_edit.text().strip()
        output_text = self.output_edit.text().strip()

        if not input_text:
            QMessageBox.warning(self, "VR360 Packager", "Выберите исходное видео.")
            return
        if not output_text:
            QMessageBox.warning(self, "VR360 Packager", "Выберите папку результата.")
            return

        try:
            renditions = self._selected_renditions()
        except Exception as exc:
            QMessageBox.critical(self, "VR360 Packager", str(exc))
            return

        if not renditions:
            QMessageBox.warning(self, "VR360 Packager", "Выберите хотя бы один профиль качества.")
            return

        options = PackageOptions(
            input_file=Path(input_text),
            output_dir=Path(output_text),
            projection=str(self.projection_combo.currentData()),
            layout=str(self.layout_combo.currentData()),
            encoder=str(self.encoder_combo.currentData()),
            segment_duration=float(self.segment_spin.value()),
            audio_bitrate_kbps=int(self.audio_spin.value()),
            renditions=renditions,
        )

        self.log.clear()
        self.progress.setValue(0)
        self.status_label.setText("Подготовка...")
        self.start_button.setEnabled(False)

        thread = QThread(self)
        worker = PackagerWorker(options)
        worker.moveToThread(thread)

        thread.started.connect(worker.run)
        worker.progress.connect(self._on_progress)
        worker.log.connect(self._append_log)
        worker.finished.connect(self._on_finished)
        worker.failed.connect(self._on_failed)

        worker.finished.connect(thread.quit)
        worker.failed.connect(thread.quit)
        thread.finished.connect(worker.deleteLater)
        thread.finished.connect(thread.deleteLater)
        thread.finished.connect(self._thread_finished)

        self._thread = thread
        self._worker = worker
        thread.start()

    @Slot(float)
    def _on_progress(self, value: float) -> None:
        value = max(0.0, min(1.0, value))
        self.progress.setValue(int(value * 1000))
        self.status_label.setText(f"Подготовка: {value * 100:.1f}%")

    @Slot(str)
    def _append_log(self, message: str) -> None:
        self.log.append(message)

    @Slot()
    def _on_finished(self) -> None:
        self.progress.setValue(1000)
        self.status_label.setText("Готово")
        QMessageBox.information(
            self,
            "VR360 Packager",
            "Пакет HLS/CMAF успешно подготовлен.",
        )

    @Slot(str)
    def _on_failed(self, message: str) -> None:
        self.status_label.setText("Ошибка")
        self.log.append(f"ERROR: {message}")
        QMessageBox.critical(self, "VR360 Packager", message)

    @Slot()
    def _thread_finished(self) -> None:
        self._thread = None
        self._worker = None
        self.start_button.setEnabled(True)

    def _open_output(self) -> None:
        directory = self.output_edit.text().strip()
        if not directory:
            return

        path = Path(directory)
        if not path.exists():
            QMessageBox.warning(self, "VR360 Packager", "Папка результата ещё не существует.")
            return

        from PySide6.QtCore import QUrl
        from PySide6.QtGui import QDesktopServices

        QDesktopServices.openUrl(QUrl.fromLocalFile(str(path.resolve())))


def run_gui() -> int:
    app = QApplication.instance() or QApplication([])
    window = MainWindow()
    window.show()
    return app.exec()
