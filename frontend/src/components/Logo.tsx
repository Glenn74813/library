const SRC = `${import.meta.env.BASE_URL}zscmst-logo-192.png`
const SRC_LARGE = `${import.meta.env.BASE_URL}zscmst-logo.png`

/** Official ZSCMST seal. Size it with CSS (.logo-img) or the size prop. */
export function Logo({ size, large, className }: { size?: number; large?: boolean; className?: string }) {
  return (
    <img src={large ? SRC_LARGE : SRC} alt="Zamboanga State College of Marine Sciences and Technology seal"
      className={`logo-img ${className ?? ''}`} width={size} height={size} />
  )
}
