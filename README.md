# ItemReplace

把世界中的指定物品，被動替換成其他物品的 Paper 26.3 (標準穩定版) 插件。支援含 NBT（資料元件）的物品，規則全部在箱子介面裡用左鍵設定。

- 支援版本：Paper 26.3 (標準穩定版) 26.1 以上（Java 25）；Purpur 等 Paper 26.3 (標準穩定版) 分支可用，Spigot、Folia 不支援
- 目前版本：1.0.1


> **版本檢查提示**：若伺服器有安裝 [HttpHub](https://github.com/clre20/HttpHub)，本插件自動由 HttpHub 集中處理版本檢查與管理員登入通知，避免各插件自開連線與背景執行緒。

## 功能特性

- **規則**
  - 每條規則：左邊 1 個「目標」，右邊 1～9 種「替換成」的物品
  - 每 1 個目標會換成右邊全部的物品，數量照格子裡的數量（64 個目標就給 64 份）
  - 右邊全部留空 = 直接刪除目標物品
- **比對方式（每條規則各自選擇）**
  - `完全相符`：材質與所有 NBT 都一樣才替換
  - `只比材質`：不管 NBT，同材質就替換
- **被動替換，不掃描世界**
  - 玩家身上：登入時、背包格子有變動時（含撿起物品）
  - 容器：玩家開啟時（箱子、木桶、界伏盒、漏斗、終界箱、礦車、驢子等）
  - 地上的掉落物：生成時、所在區塊載入時
  - 物品展示框、盔甲座、物品展示實體：所在區塊載入時
  - 界伏盒、束口袋裡面的物品也會檢查（預設往內 3 層）
- **放不下的物品**
  - 先放進同一個背包或容器，放不下再掉在地上（`overflow.mode` 可改成直接捨棄）
  - 單次最多掉落 `overflow.max-drops` 組，超過的捨棄並在後台警告
- **防止無限循環**
  - 規則換出來的物品如果會再被其他規則替換，這條規則會自動暫停並提示
- **其他**
  - `itemreplace.bypass`：持有者身上、丟出、開啟的容器都不會被替換（OP 預設也沒有）
  - 替換紀錄寫在 `logs/replace-日期.log`，保留 14 天
  - 所有文字都在 `messages.yml`；`config.yml`、`messages.yml` 缺少的項目會自動補齊
  - 啟動時非同步檢查 GitHub Releases，結果快取 10 分鐘

## 指令與權限

| 指令 | 說明 | 執行者 | 權限節點 | 預設 |
|---|---|---|---|---|
| `/itemreplace help` | 顯示說明 | 玩家、後台 | `itemreplace.help` | OP |
| `/itemreplace gui` | 開啟規則介面 | 玩家 | `itemreplace.gui` | OP |
| `/itemreplace list` | 列出所有規則 | 玩家、後台 | `itemreplace.list` | OP |
| `/itemreplace create <名稱>` | 建立規則並開啟編輯介面 | 玩家 | `itemreplace.create` | OP |
| `/itemreplace edit <名稱>` | 編輯規則 | 玩家 | `itemreplace.edit` | OP |
| `/itemreplace remove <名稱>` | 刪除規則 | 玩家、後台 | `itemreplace.remove` | OP |
| `/itemreplace toggle <名稱>` | 啟用或停用規則 | 玩家、後台 | `itemreplace.toggle` | OP |
| `/itemreplace reload` | 重新載入設定檔 | 玩家、後台 | `itemreplace.reload` | OP |
| `/itemreplace version` | 檢查更新 | 玩家、後台 | `itemreplace.version` | OP |
| — | 以上全部 | — | `itemreplace.admin` | OP |
| — | 物品不會被替換 | — | `itemreplace.bypass` | 無 |

> - 別名：`/ir`
> - 規則名稱只能用英文小寫、數字、`_`、`-`，最多 32 個字
> - 有 `itemreplace.version` 的玩家登入 3 秒後，若有新版本會收到提醒
> - `itemreplace.admin` 不包含 `itemreplace.bypass`

## 使用範例

### 1. 把舊的活動物品換成新物品

1. 手上準備好舊物品和新物品
2. 輸入指令開啟編輯介面
```bash
/ir create old-sword
```
3. 左鍵點自己背包裡的舊物品（選取），再左鍵點左邊的目標格
4. 左鍵點背包裡的新物品，用上方的 `-10` `-1` `+1` `+10` 調整數量，再點右邊的替換格
5. 左鍵點「儲存」，規則立即生效

- 物品只會被複製，不會從背包被拿走
- 沒按「儲存」就關閉，變更會被放棄

### 2. 直接刪除違規物品

```bash
/ir create banned-item
```

- 只放目標，右邊全部留空，儲存
- 想連同改過名稱、附魔的同材質物品一起刪除，把比對方式切成「只比材質」

### 3. 暫時停用與刪除規則

```bash
/ir toggle old-sword
/ir remove old-sword
```

## 設定檔 config.yml

| 項目 | 預設 | 說明 |
|---|---|---|
| `triggers.player-inventory` | `true` | 檢查玩家身上 |
| `triggers.container-open` | `true` | 開啟容器時檢查 |
| `triggers.item-spawn` | `true` | 物品掉到地上時檢查 |
| `triggers.entities-load` | `true` | 區塊實體載入時檢查 |
| `nested.enabled` / `nested.max-depth` | `true` / `3` | 界伏盒、束口袋內的物品與層數（1～5） |
| `overflow.mode` / `overflow.max-drops` | `DROP` / `64` | 放不下的物品：`DROP` 掉落、`DISCARD` 捨棄 |
| `limits.max-rules` | `500` | 規則數量上限 |
| `limits.max-item-size-kb` | `256` | 單一物品資料大小上限 |
| `log.enabled` / `log.console` | `true` / `false` | 替換紀錄寫檔／顯示在後台 |
| `log.retention-days` / `log.max-file-size-mb` | `14` / `10` | 紀錄保留天數／單檔上限 |
| `storage.save-interval-seconds` | `10` | 規則有變動時多久寫入一次 |
| `update-checker.check-on-startup` | `true` | 啟動時檢查更新 |
| `update-checker.notify-on-join` | `true` | 登入時提醒有新版本 |

## 注意事項

- 被動替換的意思是「物品被碰到才換」：沒人開過的箱子、沒載入的區塊裡的物品會維持原樣，直到被碰到
- 物品展示框、盔甲座上的物品在區塊載入時才檢查；規則建立時已經載入的，要等該區塊重新載入
- 其他插件自己做的選單介面不會被替換
- 規則存在 `rules.yml`（物品以 Base64 保存），請用介面編輯，不要手動修改

---

# ItemReplace (English)


> **Update Checker**: When [HttpHub](https://github.com/clre20/HttpHub) is installed, update checks and operator login alerts are handled centrally by HttpHub via virtual threads, preventing redundant background connections.

A Paper 26.3 (標準穩定版) plugin that passively replaces specific items in the world with other items. Items with NBT (data components) are supported, and every rule is set up in a chest GUI with left clicks.

- Supported: Paper 26.3 (標準穩定版) 26.1+ (Java 25); Paper 26.3 (標準穩定版) forks such as Purpur work, Spigot and Folia are not supported
- Current version: 1.0.1

## Features

- **Rules**
  - Each rule has 1 "target" on the left and 1–9 "replace with" items on the right
  - Every single target becomes all of the items on the right, using the amounts in the slots
  - Leaving the right side empty deletes the target item
- **Match mode (per rule)**
  - `Exact`: material and all NBT must be identical
  - `Material only`: NBT is ignored
- **Passive replacement, no world scanning**
  - Players: on join and whenever an inventory slot changes (including pickups)
  - Containers: when a player opens one (chests, barrels, shulker boxes, hoppers, ender chests, minecarts, donkeys, …)
  - Dropped items: when they spawn and when their chunk loads
  - Item frames, armor stands, item displays: when their chunk loads
  - Items inside shulker boxes and bundles are checked too (3 levels deep by default)
- **Overflow**
  - Extra items go into the same inventory first, then drop on the ground (`overflow.mode` can discard instead)
  - At most `overflow.max-drops` stacks are dropped per replacement
- **Loop protection**
  - A rule whose output would be replaced again by another rule is paused automatically
- **Other**
  - `itemreplace.bypass`: the holder's items are never replaced (not granted to OPs by default)
  - Replacement log in `logs/replace-<date>.log`, kept for 14 days
  - All text lives in `messages.yml`; missing entries in `config.yml` and `messages.yml` are filled in automatically
  - Asynchronous GitHub Releases check on startup, cached for 10 minutes

## Commands & Permissions

| Command | Description | Executor | Permission | Default |
|---|---|---|---|---|
| `/itemreplace help` | Show help | Player, console | `itemreplace.help` | OP |
| `/itemreplace gui` | Open the rule GUI | Player | `itemreplace.gui` | OP |
| `/itemreplace list` | List all rules | Player, console | `itemreplace.list` | OP |
| `/itemreplace create <name>` | Create a rule and open the editor | Player | `itemreplace.create` | OP |
| `/itemreplace edit <name>` | Edit a rule | Player | `itemreplace.edit` | OP |
| `/itemreplace remove <name>` | Delete a rule | Player, console | `itemreplace.remove` | OP |
| `/itemreplace toggle <name>` | Enable or disable a rule | Player, console | `itemreplace.toggle` | OP |
| `/itemreplace reload` | Reload the config files | Player, console | `itemreplace.reload` | OP |
| `/itemreplace version` | Check for updates | Player, console | `itemreplace.version` | OP |
| — | All of the above | — | `itemreplace.admin` | OP |
| — | Items are not replaced | — | `itemreplace.bypass` | none |

> - Alias: `/ir`
> - Rule names: lowercase letters, digits, `_` and `-`, up to 32 characters
> - Players with `itemreplace.version` are notified 3 seconds after joining when a new version exists
> - `itemreplace.admin` does not include `itemreplace.bypass`

## Usage Examples

### 1. Swap an old event item for a new one

1. Have the old and new items in your inventory
2. Open the editor
```bash
/ir create old-sword
```
3. Left-click the old item in your own inventory to select it, then left-click the target slot on the left
4. Left-click the new item, adjust the amount with `-10` `-1` `+1` `+10`, then click a slot on the right
5. Left-click "Save"; the rule takes effect immediately

- Items are only copied, never taken from your inventory
- Closing without saving discards the changes

### 2. Delete a banned item

```bash
/ir create banned-item
```

- Set only the target, leave the right side empty, and save
- Switch the match mode to "Material only" to also remove renamed or enchanted copies

### 3. Disable or delete a rule

```bash
/ir toggle old-sword
/ir remove old-sword
```

## Notes

- Passive means an item is replaced only when it is touched: items in unopened chests or unloaded chunks stay as they are until then
- Items on item frames and armor stands are checked when their chunk loads
- Menus created by other plugins are never touched
- Rules are stored in `rules.yml` (items as Base64); edit them through the GUI