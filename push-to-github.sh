#!/bin/sh
# 在 ColorReader 目录下执行此脚本
# 使用方式：
#   1. 先在 https://github.com/new 创建一个空仓库（不要勾选 README/gitignore/license）
#   2. 然后运行：sh push-to-github.sh

set -e

REPO_URL="https://github.com/yanggggg666/ColorReader.git"

echo "==> 添加 remote..."
git remote remove origin 2>/dev/null || true
git remote add origin "$REPO_URL"

echo "==> 推送到 GitHub..."
git branch -M main
git push -u origin main --force

echo ""
echo "==> 完成！仓库地址："
echo "    https://github.com/yanggggg666/ColorReader"
echo ""
echo "    GitHub Actions 将自动开始编译，可在 Actions 页面查看进度。"
echo "    编译产物（APK）在 Actions 运行完成后可下载。"
