//! ANSI frame renderer: a remote desktop frame becomes coloured half-block
//! cells in the console.
//!
//! Each character cell shows two vertically stacked pixels: the upper half
//! block `▀` paints the top pixel with the foreground colour and the bottom
//! pixel with the background colour. Cells whose colour did not change since
//! the previous frame are skipped, which is what keeps the output bandwidth
//! manageable (a full 160x50 repaint is ~190 KB, a typical diff a few KB).

use std::{io, io::Write};

use scrap::{ImageFormat, ImageRgb};

pub const ENTER_SCREEN: &[u8] = b"\x1b[?1049h\x1b[?25l\x1b[2J\x1b[H";
pub const LEAVE_SCREEN: &[u8] = b"\x1b[0m\x1b[?25h\x1b[?1049l";
const HALF_BLOCK: &[u8] = "▀".as_bytes();

pub struct Renderer {
    cols: usize,
    rows: usize,
    truecolor: bool,
    /// Packed `(top << 32) | bottom` per cell of the last painted frame.
    prev: Vec<u64>,
    out: Vec<u8>,
    painted: bool,
    pub frames: u64,
    pub bytes: u64,
}

impl Renderer {
    pub fn new(cols: usize, rows: usize, truecolor: bool) -> Self {
        Self {
            cols,
            rows,
            truecolor,
            prev: Vec::new(),
            out: Vec::new(),
            painted: false,
            frames: 0,
            bytes: 0,
        }
    }

    pub fn set_size(&mut self, cols: usize, rows: usize) {
        if (cols, rows) != (self.cols, self.rows) {
            self.cols = cols;
            self.rows = rows;
            self.prev.clear();
            self.painted = false;
        }
    }

    pub fn size(&self) -> (usize, usize) {
        (self.cols, self.rows)
    }

    /// Paint one frame plus a status line. Returns the bytes written.
    pub fn render(&mut self, image: &ImageRgb, status: &str) -> io::Result<usize> {
        let cols = self.cols.max(1);
        let rows = self.rows.max(2);
        let cells = cols * (rows - 1);
        if self.prev.len() != cells {
            self.prev = vec![u64::MAX; cells];
            self.painted = false;
        }
        self.out.clear();
        if !self.painted {
            self.out.extend_from_slice(b"\x1b[2J");
        }
        let (w, h) = (image.w, image.h);
        if w == 0 || h == 0 {
            return Ok(0);
        }
        let mut last_row = usize::MAX;
        for row in 0..rows - 1 {
            let y0 = row * h / (rows - 1);
            let y1 = ((row + 1) * h / (rows - 1)).max(y0 + 1).min(h);
            let (mut run_start, mut run) = (usize::MAX, 0usize);
            for col in 0..cols {
                let x0 = col * w / cols;
                let x1 = ((col + 1) * w / cols).max(x0 + 1).min(w);
                let (top, bottom) = sample_cell(image, x0, x1, y0, y1);
                let packed = ((top as u64) << 32) | bottom as u64;
                let index = row * cols + col;
                if self.prev[index] == packed {
                    if run_start != usize::MAX {
                        self.emit_run(last_row, run_start, run);
                        run_start = usize::MAX;
                        run = 0;
                    }
                    continue;
                }
                self.prev[index] = packed;
                if run_start == usize::MAX {
                    run_start = col;
                    run = 1;
                } else {
                    run += 1;
                }
                if last_row != row {
                    last_row = row;
                }
            }
            if run_start != usize::MAX {
                self.emit_run(row, run_start, run);
            }
        }
        self.paint_status(status);
        let mut stdout = io::stdout().lock();
        stdout.write_all(&self.out)?;
        stdout.flush()?;
        self.painted = true;
        self.frames += 1;
        self.bytes += self.out.len() as u64;
        Ok(self.out.len())
    }

    /// Emit a contiguous run of cells in one CUP + colour sequence.
    fn emit_run(&mut self, row: usize, start: usize, len: usize) {
        write!(self.out, "\x1b[{};{}H", row + 1, start + 1).ok();
        let cols = self.cols;
        for col in start..start + len {
            let packed = self.prev[row * cols + col];
            let top = (packed >> 32) as u32;
            let bottom = packed as u32;
            if self.truecolor {
                write!(
                    self.out,
                    "\x1b[38;2;{};{};{}m\x1b[48;2;{};{};{}m",
                    (top >> 16) & 0xFF,
                    (top >> 8) & 0xFF,
                    top & 0xFF,
                    (bottom >> 16) & 0xFF,
                    (bottom >> 8) & 0xFF,
                    bottom & 0xFF
                )
                .ok();
            } else {
                write!(
                    self.out,
                    "\x1b[38;5;{}m\x1b[48;5;{}m",
                    ansi256(top),
                    ansi256(bottom)
                )
                .ok();
            }
            self.out.extend_from_slice(HALF_BLOCK);
        }
        self.out.extend_from_slice(b"\x1b[0m");
    }

    fn paint_status(&mut self, status: &str) {
        let row = self.rows;
        let text: String = status.chars().take(self.cols).collect();
        write!(self.out, "\x1b[{};1H\x1b[0m\x1b[7m", row).ok();
        self.out.extend_from_slice(text.as_bytes());
        for _ in text.chars().count()..self.cols {
            self.out.push(b' ');
        }
        self.out.extend_from_slice(b"\x1b[0m");
    }
}

/// Average the top and bottom halves of one cell down to two colours.
fn sample_cell(image: &ImageRgb, x0: usize, x1: usize, y0: usize, y1: usize) -> (u32, u32) {
    let mid = ((y0 + y1) / 2).max(y0 + 1).min(y1);
    let top = average(image, x0, x1, y0, mid);
    let bottom = average(image, x0, x1, mid, y1);
    (top, bottom)
}

fn average(image: &ImageRgb, x0: usize, x1: usize, y0: usize, y1: usize) -> u32 {
    // Sample at most a 2x2 grid: enough to keep gradients readable while
    // staying cheap at 30 fps.
    let xs = [x0, ((x0 + x1) / 2).min(x1 - 1)];
    let ys = [y0, ((y0 + y1) / 2).min(y1 - 1)];
    let (mut r, mut g, mut b, mut n) = (0u32, 0u32, 0u32, 0u32);
    for &y in ys.iter() {
        for &x in xs.iter() {
            let (pr, pg, pb) = pixel(image, x, y);
            r += pr as u32;
            g += pg as u32;
            b += pb as u32;
            n += 1;
        }
    }
    let n = n.max(1);
    ((r / n) << 16) | ((g / n) << 8) | (b / n)
}

#[inline]
fn pixel(image: &ImageRgb, x: usize, y: usize) -> (u8, u8, u8) {
    let raw = &image.raw;
    match image.fmt {
        ImageFormat::Raw => {
            let index = (y * image.w + x) * 3;
            if index + 2 < raw.len() {
                (raw[index], raw[index + 1], raw[index + 2])
            } else {
                (0, 0, 0)
            }
        }
        ImageFormat::ARGB => {
            let index = (y * image.w + x) * 4;
            if index + 3 < raw.len() {
                (raw[index + 1], raw[index + 2], raw[index + 3])
            } else {
                (0, 0, 0)
            }
        }
        ImageFormat::ABGR => {
            let index = (y * image.w + x) * 4;
            if index + 3 < raw.len() {
                (raw[index + 3], raw[index + 2], raw[index + 1])
            } else {
                (0, 0, 0)
            }
        }
    }
}

fn ansi256(rgb: u32) -> u8 {
    let r = ((rgb >> 16) & 0xFF) as u32;
    let g = ((rgb >> 8) & 0xFF) as u32;
    let b = (rgb & 0xFF) as u32;
    let levels = [0u32, 95, 135, 175, 215, 255];
    let index = |v: u32| {
        levels
            .iter()
            .enumerate()
            .min_by_key(|(_, level)| (*level).abs_diff(v))
            .map(|(i, _)| i)
            .unwrap_or(0)
    };
    (16 + 36 * index(r) + 6 * index(g) + index(b)) as u8
}
