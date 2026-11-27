# Как работать вдвоём над «Сеансом»

Репозиторий приватный: https://github.com/leojakee/seans

## 1. Первый раз (другу)

1. Принять приглашение: письмо от GitHub или https://github.com/leojakee/seans/invitations
2. Установить Git: https://git-scm.com/download/win (галочки по умолчанию).
3. Склонировать проект в PowerShell:

```powershell
git clone https://github.com/leojakee/seans.git
cd seans
git config user.name "Твой ник"
git config user.email "почта@из.github"
```

При первом `clone` или `push` откроется окно входа в GitHub, войти через браузер. Токены вручную делать не нужно.

4. Открыть папку в Android Studio. Файл `local.properties` (путь к SDK) Studio создаст сам, в git он не попадает.

## 2. Каждый день

Перед началом работы забрать свежие изменения:

```powershell
git pull
```

Закончил кусок работы, отправить его:

```powershell
git add -A
git commit -m "Коротко: что сделал"
git pull
git push
```

`git pull` перед `push` нужен, чтобы подтянуть то, что успел отправить другой.

## 3. Ветки (когда задача больше одного вечера)

```powershell
git switch -c feature/nazvanie     # новая ветка
# ...работа, commit...
git push -u origin feature/nazvanie
```

Дальше на GitHub нажать **Compare & pull request**, второй смотрит и жмёт **Merge**. После слияния:

```powershell
git switch main
git pull
```

## 4. Если конфликт

`git pull` пишет `CONFLICT` в файле. Открыть файл, найти блоки `<<<<<<<`, `=======`, `>>>>>>>`, оставить нужный вариант, удалить метки, затем:

```powershell
git add -A
git commit
git push
```

## 5. Что нельзя коммитить

Ключи подписи (`*.jks`, `*.keystore`), пароли, `local.properties`, папки `build/` и `.gradle/`. Они уже в `.gitignore`.
